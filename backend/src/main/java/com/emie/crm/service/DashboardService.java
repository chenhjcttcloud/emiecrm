package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only business aggregation; the dashboard never rewrites orders or cash ledgers. */
@Service
public class DashboardService {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private static final String TARGET_SELECT =
      "SELECT month_key,amount,revision,updated_at,updated_by FROM dashboard_targets WHERE month_key=?";
  private static final String PERSONNEL_TARGET_SELECT =
      "SELECT month_key,owner,amount FROM sales_targets WHERE month_key=?";
  private final CrmRepository db;
  private final SalesTargetScopeService scopes;
  @Value("${CRM_JST_ENV:sandbox}") private String environment = "sandbox";

  public DashboardService(CrmRepository db, SalesTargetScopeService scopes) { this.db = db; this.scopes = scopes; }

  private static YearMonth month(String value) {
    try {
      require(value != null && value.matches("[0-9]{4}-[0-9]{2}"), "请选择有效月份");
      var result = YearMonth.parse(value);
      require(result.getYear() >= 2000 && result.getYear() <= 9999, "请选择2000年至9999年的月份");
      return result;
    } catch (DateTimeParseException error) { throw new ApiException("请选择有效月份"); }
  }

  private static LocalDate date(String value) {
    if (value == null || value.isBlank()) return null;
    try { return OffsetDateTime.parse(value).atZoneSameInstant(SHANGHAI).toLocalDate(); }
    catch (DateTimeParseException ignored) { }
    try { return LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate(); }
    catch (DateTimeParseException ignored) { }
    try { return LocalDate.parse(value); }
    catch (DateTimeParseException ignored) { return null; }
  }

  private static BigDecimal amount(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) return ZERO;
    String value = node.asText();
    if (value.length() > 40 || !value.matches("-?[0-9]+(?:\\.[0-9]{1,2})?"))
      throw new IllegalArgumentException("Invalid stored amount");
    return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
  }

  // JSON decimal strings preserve cents even when a report sum exceeds JavaScript's safe integer range.
  private static String decimalText(Object value) {
    if (value == null) return null;
    var decimal = value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
    return decimal.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private static String customerId(JsonNode record, Map<String, JsonNode> customers, Map<String, String> uniqueNames) {
    String id = record.path("customerId").asText();
    // An explicit legacy ID is never silently reassigned to a different customer.
    return id.isBlank() ? uniqueNames.getOrDefault(record.path("customer").asText(), "") : id;
  }

  private static String customerName(JsonNode record, String id, Map<String, JsonNode> customers) {
    var customer = customers.get(id);
    return customer == null ? record.path("customer").asText() : customer.path("name").asText();
  }

  private static void identity(Set<String> keys, String env, String shop, String oid, String soid) {
    if (!oid.isBlank() && !oid.equals("0")) keys.add(env + ":o:" + oid);
    if (!shop.isBlank() && !soid.isBlank()) keys.add(env + ":s:" + shop + ":" + soid);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> sales(String selectedMonth, String selectedOwner, String selectedCustomer, Actor actor) {
    actor.require("dashboard.read");
    return salesReport(selectedMonth, selectedOwner, selectedCustomer);
  }

  // Shared aggregation used by separately authorized modules in the same read transaction.
  Map<String, Object> salesReport(String selectedMonth, String selectedOwner, String selectedCustomer) {
    return salesReport(selectedMonth, selectedOwner, selectedCustomer, scopes.snapshot());
  }

  Map<String, Object> salesReport(String selectedMonth, String selectedOwner, String selectedCustomer,
                                  SalesTargetScopeService.Snapshot scope) {
    var period = month(selectedMonth);
    String owner = selectedOwner == null ? "" : selectedOwner;
    String requestedCustomer = selectedCustomer == null ? "" : selectedCustomer;
    require(owner.length() <= 191 && requestedCustomer.length() <= 191, "筛选条件过长");
    var warnings = new LinkedHashSet<String>();
    JsonNode state = db.state(false);
    var customers = new LinkedHashMap<String, JsonNode>();
    var uniqueNames = new HashMap<String, String>();
    var ambiguous = new HashSet<String>();
    for (var customer : state.path("customers")) {
      String id = customer.path("id").asText(), name = customer.path("name").asText();
      customers.put(id, customer);
      if (uniqueNames.put(name, id) != null) ambiguous.add(name);
    }
    ambiguous.forEach(uniqueNames::remove);
    var ordersById = new LinkedHashMap<String, JsonNode>();
    var ownerOptions = new TreeSet<String>();
    for (var order : state.path("orders")) {
      ordersById.put(order.path("id").asText(), order);
      if (!order.hasNonNull("deletedAt") && !"已取消".equals(order.path("status").asText())
          && !order.path("owner").asText().isBlank()) ownerOptions.add(order.path("owner").asText());
    }

    var shipments = new HashMap<String, JsonNode>();
    var identities = new HashMap<String, Set<String>>();
    for (var row : db.rows("SELECT order_id,payload FROM jst_shipments")) {
      String id = row.get("order_id").toString();
      var shipment = db.parse(row.get("payload").toString());
      String env = shipment.path("environment").asText();
      if (env.equals(environment)) shipments.put(id, shipment);
      var keys = identities.computeIfAbsent(id, ignored -> new HashSet<>());
      String shop = shipment.path("request").path("shop_id").asText();
      identity(keys, env, shop, shipment.path("oId").asText(), shipment.path("soId").asText());
      for (var amendment : shipment.path("amendments"))
        identity(keys, env, shop, amendment.path("oldOId").asText(), amendment.path("oldSoId").asText());
      var amendment = shipment.path("amendment");
      identity(keys, env, shop, amendment.path("oldOId").asText(), amendment.path("oldSoId").asText());
    }
    for (var row : db.rows("SELECT environment,order_id,shop_id,o_id,so_id FROM jst_document_sales")) {
      var keys = identities.computeIfAbsent(row.get("order_id").toString(), ignored -> new HashSet<>());
      identity(keys, Objects.toString(row.get("environment"), ""), Objects.toString(row.get("shop_id"), ""),
          Objects.toString(row.get("o_id"), ""), Objects.toString(row.get("so_id"), ""));
    }

    // Ordinary orders win defensive duplicate checks before settlement totals are considered.
    var candidates = ordersById.values().stream()
        .filter(o -> !o.hasNonNull("deletedAt") && "销售订单".equals(o.path("type").asText())
            && "已批准".equals(o.path("status").asText()))
        .sorted(Comparator.<JsonNode, Boolean>comparing(o -> "jushuitan-settlement".equals(o.path("source").asText()))
            .thenComparing(o -> o.path("id").asText())).toList();
    var usedIdentities = new HashSet<String>();
    var excludedIds = new HashSet<String>();
    for (var order : candidates) {
      String id = order.path("id").asText();
      var keys = identities.getOrDefault(id, Set.of());
      if (keys.stream().anyMatch(usedIdentities::contains)) {
        excludedIds.add(id);
        warnings.add("存在重复关联的聚水潭销售单，已排除订单 " + OrderNumbers.display(order) + "，请核对关联；未按比例分摊补录总额。");
      } else usedIdentities.addAll(keys);
    }

    BigDecimal orderAmount = ZERO, settledAmount = ZERO, outstandingAmount = ZERO;
    int settledCount = 0, pendingCount = 0;
    var orderRows = new ArrayList<Map<String, Object>>();
    var dailyAmounts = new LinkedHashMap<String, BigDecimal>();
    var dailyCounts = new HashMap<String, Integer>();
    for (int day = 1; day <= period.lengthOfMonth(); day++) dailyAmounts.put(period.atDay(day).toString(), ZERO);
    for (var order : candidates) {
      String id = order.path("id").asText();
      if (excludedIds.contains(id)) continue;
      String orderNumber = OrderNumbers.display(order);
      String cid = customerId(order, customers, uniqueNames), signer = order.path("owner").asText();
      if (!owner.isEmpty() && !owner.equals(signer) || !requestedCustomer.isEmpty() && !requestedCustomer.equals(cid)) continue;
      boolean settlement = "jushuitan-settlement".equals(order.path("source").asText());
      LocalDate businessDate;
      if (settlement) {
        var start = date(order.path("settlement").path("periodStart").asText());
        var end = date(order.path("settlement").path("periodEnd").asText());
        if (start == null || end == null || end.isBefore(start)) {
          warnings.add("结算订单 " + orderNumber + " 的所属期间无效，暂未计入。"); continue;
        }
        if (!YearMonth.from(start).equals(YearMonth.from(end))) {
          if (!start.isAfter(period.atEndOfMonth()) && !end.isBefore(period.atDay(1)))
            warnings.add("跨月结算订单 " + orderNumber + " 暂未计入，请拆分或核对所属月份；系统不平均分摊金额。");
          continue;
        }
        businessDate = end;
        if (YearMonth.from(end).equals(period)) warnings.add("结算补录按所属期间归月，每日图表集中展示在结算期末日。");
      } else businessDate = date(order.path("created").asText());
      if (businessDate == null) { warnings.add("订单 " + orderNumber + " 缺少有效创建日期，暂未计入。"); continue; }
      if (!YearMonth.from(businessDate).equals(period)) continue;
      var shipment = shipments.get(id);
      var effective = shipment == null ? order : shipment.path("effectiveOrder");
      BigDecimal total, paid;
      try {
        var totalNode = effective.hasNonNull("total") ? effective.path("total") : order.path("total");
        if (totalNode.isMissingNode() || totalNode.isNull()) throw new IllegalArgumentException();
        total = amount(totalNode);
        paid = amount(effective.hasNonNull("paid") ? effective.path("paid") : order.path("paid"));
        if (total.signum() < 0 || paid.signum() < 0) throw new IllegalArgumentException();
      } catch (IllegalArgumentException error) { warnings.add("订单 " + orderNumber + " 的金额异常，暂未计入。"); continue; }
      if (shipment != null && "CANCELLED".equals(shipment.path("status").asText()))
        warnings.add("订单 " + orderNumber + " 的发货已取消，取消发货不等于退款；业绩仍按 CRM 已生效金额统计，请核对。");
      var outstanding = total.subtract(paid).max(ZERO);
      boolean settled = total.signum() > 0 && paid.compareTo(total) >= 0;
      if (settled) settledCount++;
      if (outstanding.signum() > 0) pendingCount++;
      if (paid.compareTo(total) > 0) warnings.add("订单 " + orderNumber + " 的累计结算额超过有效订单金额，请核对。");
      orderAmount = orderAmount.add(total); settledAmount = settledAmount.add(paid); outstandingAmount = outstandingAmount.add(outstanding);
      String day = businessDate.toString();
      dailyAmounts.put(day, dailyAmounts.get(day).add(total)); dailyCounts.merge(day, 1, Integer::sum);
      var row = new LinkedHashMap<String, Object>();
      row.put("id", id); row.put("date", day); row.put("owner", signer); row.put("customerId", cid);
      row.put("orderNumber", orderNumber);
      row.put("customer", customerName(order, cid, customers)); row.put("total", decimalText(total)); row.put("paid", decimalText(paid));
      row.put("outstanding", decimalText(outstanding)); row.put("settled", settled); row.put("source", order.path("source").asText());
      orderRows.add(row);
    }
    orderRows.sort(Comparator.<Map<String, Object>, String>comparing(r -> r.get("date").toString()).reversed()
        .thenComparing(r -> r.get("id").toString()));

    BigDecimal advanceReceived = ZERO, advanceBalance = ZERO;
    var advanceRows = new ArrayList<Map<String, Object>>();
    for (var receipt : db.documents("receipts", false, 0)) {
      if (!"预收款订单".equals(receipt.path("type").asText()) || "预收款核销".equals(receipt.path("method").asText())) continue;
      var origin = ordersById.get(receipt.path("order").asText());
      String signer = origin == null ? "" : origin.path("owner").asText();
      if (!signer.isBlank()) ownerOptions.add(signer);
      String cid = receipt.path("customerId").asText();
      if (cid.isBlank() && origin != null) cid = customerId(origin, customers, uniqueNames);
      if (cid.isBlank()) cid = uniqueNames.getOrDefault(receipt.path("customer").asText(), "");
      if (!owner.isEmpty() && !owner.equals(signer) || !requestedCustomer.isEmpty() && !requestedCustomer.equals(cid)) continue;
      var registered = date(receipt.path("time").asText());
      if (registered == null) { warnings.add("预收款流水 " + receipt.path("id").asText() + " 的登记日期无效，暂未计入。"); continue; }
      if (!YearMonth.from(registered).equals(period)) continue;
      BigDecimal received;
      try { received = amount(receipt.path("amount")); }
      catch (IllegalArgumentException error) { warnings.add("预收款流水金额异常，暂未计入。"); continue; }
      advanceReceived = advanceReceived.add(received);
      var row = new LinkedHashMap<String, Object>();
      row.put("id", receipt.path("id").asText()); row.put("date", registered.toString());
      row.put("order", receipt.path("order").asText()); row.put("customer", customerName(receipt, cid, customers));
      row.put("orderNumber", origin == null ? receipt.path("order").asText() : OrderNumbers.display(origin));
      row.put("owner", signer); row.put("amount", decimalText(received)); row.put("method", receipt.path("method").asText());
      advanceRows.add(row);
    }
    if (owner.isEmpty()) for (var customer : customers.values()) {
      if (!requestedCustomer.isEmpty() && !requestedCustomer.equals(customer.path("id").asText())) continue;
      try { advanceBalance = advanceBalance.add(amount(customer.path("balance"))); }
      catch (IllegalArgumentException error) { warnings.add("客户预收款余额异常，请核对客户资料。"); }
    }
    else warnings.add("客户当前预收款余额不按签单人分摊；选择签单人时不展示余额。");
    advanceRows.sort(Comparator.<Map<String, Object>, String>comparing(r -> r.get("date").toString()).reversed());

    var summary = new LinkedHashMap<String, Object>();
    summary.put("orderCount", orderRows.size()); summary.put("settledCount", settledCount); summary.put("pendingCount", pendingCount);
    summary.put("orderAmount", decimalText(orderAmount)); summary.put("settledAmount", decimalText(settledAmount)); summary.put("outstandingAmount", decimalText(outstandingAmount));
    summary.put("advanceReceived", decimalText(advanceReceived)); summary.put("advanceBalance", owner.isEmpty() ? decimalText(advanceBalance) : null);
    var result = new LinkedHashMap<String, Object>();
    result.put("month", period.toString()); result.put("generatedAt", CrmRepository.now()); result.put("summary", summary);
    result.put("target", target(period.toString(), scope)); result.put("targetApplicable", owner.isEmpty() && requestedCustomer.isEmpty());
    result.put("options", Map.of("owners", ownerOptions, "customers", customers.values().stream()
        .map(c -> Map.of("id", c.path("id").asText(), "name", c.path("name").asText())).toList()));
    result.put("daily", dailyAmounts.entrySet().stream().map(e -> Map.of("date", e.getKey(), "amount", decimalText(e.getValue()), "count", dailyCounts.getOrDefault(e.getKey(), 0))).toList());
    result.put("orders", orderRows); result.put("advanceReceipts", advanceRows); result.put("warnings", new ArrayList<>(warnings));
    return result;
  }

  private Map<String, Object> manualTarget(String selectedMonth, boolean lock) {
    var rows = db.rows(TARGET_SELECT + (lock ? " FOR UPDATE" : ""), selectedMonth);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private record PersonnelTotal(BigDecimal amount, int count) {
    PersonnelTotal add(BigDecimal value) { return new PersonnelTotal(amount.add(value), count + 1); }
  }

  private static Map<String, PersonnelTotal> personnelTotals(List<Map<String, Object>> rows, SalesTargetScopeService.Snapshot scope) {
    var totals = new HashMap<String, PersonnelTotal>();
    for (var row : rows) {
      String owner = Objects.toString(row.get("owner"), "");
      if (row.get("amount") == null || owner.isBlank() || !scope.includes(owner)) continue;
      String month = row.get("month_key").toString();
      var value = new BigDecimal(row.get("amount").toString());
      totals.put(month, totals.getOrDefault(month, new PersonnelTotal(ZERO, 0)).add(value));
    }
    return totals;
  }

  private static Map<String, Object> effectiveTarget(String selectedMonth, Map<String, Object> manual,
                                                    PersonnelTotal personnel, SalesTargetScopeService.Snapshot scope) {
    var result = new LinkedHashMap<String, Object>();
    result.put("month", selectedMonth);
    String manualAmount = manual == null ? null : decimalText(manual.get("amount"));
    result.put("amount", scope.selected() || personnel.count() > 0 ? decimalText(personnel.amount()) : manualAmount);
    result.put("source", scope.selected() || personnel.count() > 0 ? "personnel" : manualAmount == null ? "unset" : "manual");
    result.put("personCount", personnel.count());
    result.put("manualAmount", manualAmount);
    result.put("scope", scope.metadata());
    // Preserve the manual row's metadata for the existing compare-and-set endpoint.
    result.put("revision", manual == null ? 0L : ((Number) manual.get("revision")).longValue());
    result.put("updatedAt", manual == null ? null : manual.get("updated_at"));
    result.put("updatedBy", manual == null ? null : manual.get("updated_by"));
    return result;
  }

  private Map<String, Object> target(String selectedMonth, SalesTargetScopeService.Snapshot scope) {
    var manual = manualTarget(selectedMonth, false);
    var personnel = personnelTotals(db.rows(PERSONNEL_TARGET_SELECT, selectedMonth), scope)
        .getOrDefault(selectedMonth, new PersonnelTotal(ZERO, 0));
    return effectiveTarget(selectedMonth, manual, personnel, scope);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> targets(int year, Actor actor) {
    actor.require("dashboard.targets.manage");
    require(year >= 2000 && year <= 9999, "请选择2000年至9999年的年份");
    var scope = scopes.snapshot();
    String start = YearMonth.of(year, 1).toString(), end = YearMonth.of(year, 12).toString();
    var manualRows = db.rows("SELECT month_key,amount,revision,updated_at,updated_by FROM dashboard_targets WHERE month_key>=? AND month_key<=?", start, end);
    var manualByMonth = new HashMap<String, Map<String, Object>>();
    manualRows.forEach(row -> manualByMonth.put(row.get("month_key").toString(), row));
    var personnelByMonth = personnelTotals(db.rows(
        "SELECT month_key,owner,amount FROM sales_targets WHERE month_key>=? AND month_key<=?", start, end), scope);
    var targets = new ArrayList<Map<String, Object>>();
    for (int index = 1; index <= 12; index++) {
      String key = YearMonth.of(year, index).toString();
      targets.add(effectiveTarget(key, manualByMonth.get(key), personnelByMonth.getOrDefault(key, new PersonnelTotal(ZERO, 0)), scope));
    }
    return Map.of("year", year, "targets", targets);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> saveTarget(JsonNode body, Actor actor) {
    actor.require("dashboard.targets.manage");
    String selectedMonth = month(body.path("month").asText()).toString();
    String value = body.path("amount").asText();
    require(value.matches("[0-9]{1,12}(?:\\.[0-9]{1,2})?"), "目标金额须为非负数，最多12位整数和两位小数");
    BigDecimal targetAmount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    var version = body.path("revision");
    require(version.isIntegralNumber() && version.canConvertToLong() && version.asLong() >= 0 && version.asLong() < Long.MAX_VALUE, "请提供有效目标版本，刷新后重试");
    long revision = version.asLong();
    var scope = scopes.snapshotForWrite();
    if (scope.selected())
      throw new ApiException(409, "公司目标已按部门范围汇总，请到销售业绩目标或部门范围设置调整");
    // A current, locking prefix read also protects an empty month's insertion gap under REPEATABLE READ.
    // Personnel saves either commit before this read (and are rejected here), or wait until this save commits.
    var personnel = personnelTotals(db.rows(PERSONNEL_TARGET_SELECT + " FOR UPDATE", selectedMonth), scope)
        .getOrDefault(selectedMonth, new PersonnelTotal(ZERO, 0));
    if (personnel.count() > 0)
      throw new ApiException(409, "本月公司目标由人员目标自动汇总，请到销售业绩目标调整");
    // A placeholder row closes the first-write race. Any stale writer rolls this transaction back.
    db.jdbc().update("INSERT IGNORE INTO dashboard_targets(month_key,amount,revision,updated_at,updated_by) VALUES(?,NULL,0,NULL,NULL)", selectedMonth);
    var before = effectiveTarget(selectedMonth, manualTarget(selectedMonth, true), personnel, scope);
    if (((Number) before.get("revision")).longValue() != revision) throw new ApiException(409, "业绩目标已被其他人更新，请刷新后重试");
    int changed = db.jdbc().update("UPDATE dashboard_targets SET amount=?,revision=revision+1,updated_at=?,updated_by=? WHERE month_key=? AND revision=?",
        targetAmount, CrmRepository.now(), actor.name(), selectedMonth, revision);
    if (changed != 1) throw new ApiException(409, "业绩目标已被其他人更新，请刷新后重试");
    var after = effectiveTarget(selectedMonth, manualTarget(selectedMonth, false), personnel, scope);
    var detail = db.object().put("type", "DASHBOARD_TARGET_CHANGED").put("month", selectedMonth);
    detail.set("before", db.tree(before)); detail.set("after", db.tree(after));
    db.audit(actor.name(), "设置月度业绩目标 " + selectedMonth, detail);
    return after;
  }
}
