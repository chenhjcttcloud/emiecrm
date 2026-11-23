package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesService {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private static final String TARGETS = "SELECT month_key,owner,amount,revision,updated_at,updated_by FROM sales_targets WHERE month_key=?";
  private static final String OWNERS = "SELECT COALESCE(p.display_name,u.name) AS owner FROM users u LEFT JOIN user_profiles p ON p.name=u.name ORDER BY owner";
  private final CrmRepository db;
  private final DashboardService dashboard;
  private final SalesTargetScopeService scopes;
  @Value("${CRM_JST_ENV:sandbox}") private String environment = "sandbox";

  public SalesService(CrmRepository db, DashboardService dashboard, SalesTargetScopeService scopes) {
    this.db = db;
    this.dashboard = dashboard;
    this.scopes = scopes;
  }

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

  private static BigDecimal decimal(JsonNode node, int scale, boolean signed) {
    String raw = node.asText();
    if (raw.length() > 40 || !raw.matches((signed ? "-?" : "") + "[0-9]+(?:\\.[0-9]{1," + scale + "})?"))
      throw new IllegalArgumentException("无效数字");
    return new BigDecimal(raw);
  }

  private static String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String text(JsonNode node, String key) {
    String value = node.path(key).asText();
    return value.isBlank() ? null : value;
  }

  private record Snapshot(JsonNode state, Map<String, JsonNode> orders, Map<String, JsonNode> customers,
                          Map<String, String> names, Map<String, JsonNode> products, Map<String, JsonNode> effective) {}

  private static Map<String, JsonNode> index(JsonNode nodes) {
    var result = new LinkedHashMap<String, JsonNode>();
    for (var node : nodes) result.put(node.path("id").asText(), node);
    return result;
  }

  private Snapshot snapshot() {
    var state = db.state(false);
    var customers = index(state.path("customers"));
    var names = new HashMap<String, String>();
    var ambiguous = new HashSet<String>();
    for (var customer : customers.values()) {
      String name = customer.path("name").asText();
      if (names.put(name, customer.path("id").asText()) != null) ambiguous.add(name);
    }
    ambiguous.forEach(names::remove);
    var effective = new HashMap<String, JsonNode>();
    for (var row : db.rows("SELECT order_id,payload FROM jst_shipments")) {
      var shipment = db.parse(row.get("payload").toString());
      if (environment.equals(shipment.path("environment").asText()) && shipment.path("effectiveOrder").isObject())
        effective.put(row.get("order_id").toString(), shipment.path("effectiveOrder"));
    }
    return new Snapshot(state, index(state.path("orders")), customers, names, index(state.path("products")), effective);
  }

  private static String customerId(JsonNode record, Snapshot snapshot) {
    String id = record.path("customerId").asText();
    return id.isBlank() ? snapshot.names().getOrDefault(record.path("customer").asText(), "") : id;
  }

  private static String customerName(JsonNode record, String id, Snapshot snapshot) {
    var customer = snapshot.customers().get(id);
    return customer == null ? record.path("customer").asText() : customer.path("name").asText();
  }

  private static Map<String, Object> options(Snapshot snapshot) {
    var owners = new TreeSet<String>();
    for (var order : snapshot.orders().values())
      if (activeOrder(order) && !order.path("owner").asText().isBlank()) owners.add(order.path("owner").asText());
    return new LinkedHashMap<>(Map.of("owners", owners, "customers", snapshot.customers().values().stream()
        .map(c -> Map.of("id", c.path("id").asText(), "name", c.path("name").asText())).toList()));
  }

  private static boolean activeOrder(JsonNode order) {
    return !order.hasNonNull("deletedAt") && !"已取消".equals(order.path("status").asText());
  }

  private static void validate(int page, int pageSize, String q, String owner, String customerId) {
    require(page >= 1 && pageSize >= 1 && pageSize <= 100 && q.length() <= 200 && owner.length() <= 191 && customerId.length() <= 191,
        "分页或筛选参数无效");
  }

  private static boolean matches(String q, Object... fields) {
    if (q.isEmpty()) return true;
    for (var field : fields) if (field != null && field.toString().toLowerCase(Locale.ROOT).contains(q)) return true;
    return false;
  }

  private static Map<String, Object> page(List<Map<String, Object>> rows, int requested, int size) {
    int current = (int) Math.min(requested, Math.max(1L, (rows.size() + (long)size - 1) / size));
    int start = (current - 1) * size;
    var result = new LinkedHashMap<String, Object>();
    result.put("rows", new ArrayList<>(rows.subList(start, Math.min(start + size, rows.size()))));
    result.put("total", rows.size()); result.put("page", current); result.put("pageSize", size);
    return result;
  }

  private static BigDecimal unitCost(JsonNode line) {
    // Generic cost/scalar snapshots have no defined historical unit-cost semantics.
    // Only explicitly named unitCost fields may establish historical cost and profit.
    JsonNode value = line.path("costSnapshot").path("unitCost");
    if (value.isMissingNode() || value.isNull()) value = line.path("unitCost");
    if (value.isMissingNode() || value.isNull()) return null;
    try { return decimal(value, 2, false); }
    catch (IllegalArgumentException ignored) { return null; }
  }

  private static LocalDate orderDate(JsonNode order, Set<String> warnings) {
    if (!"jushuitan-settlement".equals(order.path("source").asText())) return date(order.path("created").asText());
    var start = date(order.path("settlement").path("periodStart").asText());
    var end = date(order.path("settlement").path("periodEnd").asText());
    if (start == null || end == null || end.isBefore(start) || !YearMonth.from(start).equals(YearMonth.from(end))) {
      warnings.add("结算订单 " + order.path("id").asText() + " 的期间无效或跨月，未推算所属日期。");
      return null;
    }
    return end;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> items(int page, int pageSize, String q, String selectedMonth, String owner, String selectedCustomer, Actor actor) {
    actor.require("business.read");
    validate(page, pageSize, q, owner, selectedCustomer);
    var period = selectedMonth.isEmpty() ? null : month(selectedMonth);
    String query = q.trim().toLowerCase(Locale.ROOT);
    var snapshot = snapshot();
    var warnings = new LinkedHashSet<String>();
    var matched = new ArrayList<Map<String, Object>>();
    for (var order : snapshot.orders().values()) {
      if (!activeOrder(order) || !"销售订单".equals(order.path("type").asText())) continue;
      String id = order.path("id").asText(), signer = order.path("owner").asText(), cid = customerId(order, snapshot);
      if (!owner.isEmpty() && !owner.equals(signer) || !selectedCustomer.isEmpty() && !selectedCustomer.equals(cid)) continue;
      var day = orderDate(order, warnings);
      if (day == null) warnings.add("订单 " + id + " 缺少有效所属日期，未推算月份。");
      if (period != null && (day == null || !YearMonth.from(day).equals(period))) continue;
      var effective = snapshot.effective().getOrDefault(id, order);
      var lines = effective.has("items") ? effective.path("items") : order.path("items");
      if (!lines.isArray() || lines.isEmpty()) {
        warnings.add("订单 " + id + " 未记录逐商品成交明细，未虚构商品数量及单价。");
        continue;
      }
      var rows = new ArrayList<Map<String, Object>>();
      BigDecimal sum = ZERO, total;
      try {
        total = decimal(effective.hasNonNull("total") ? effective.path("total") : order.path("total"), 2, false).setScale(2);
        int position = 0;
        for (var line : lines) {
          var qty = decimal(line.path("qty"), 6, false);
          if (qty.signum() <= 0) throw new IllegalArgumentException();
          var price = decimal(line.path("price"), 2, false);
          var lineAmount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
          String productId = line.path("product").asText();
          if (productId.isBlank()) throw new IllegalArgumentException();
          var product = snapshot.products().get(productId);
          var row = new LinkedHashMap<String, Object>();
          row.put("id", id + ":" + position++); row.put("orderId", id); row.put("customerId", cid);
          row.put("customer", customerName(order, cid, snapshot)); row.put("owner", signer);
          row.put("status", order.path("status").asText()); row.put("date", day == null ? null : day.toString());
          row.put("productId", productId);
          row.put("product", product == null ? Optional.ofNullable(text(line, "name")).orElse(productId) : product.path("name").asText(productId));
          row.put("code", product == null ? null : text(product, "code"));
          row.put("model", product == null ? null : text(product, "model"));
          row.put("barcode", text(line, "barcode") != null ? text(line, "barcode") : product == null ? null : text(product, "barcode"));
          row.put("listedOn", product == null ? null : text(product, "listedOn"));
          row.put("qty", qty.stripTrailingZeros().toPlainString()); row.put("price", money(price));
          row.put("amount", lineAmount);
          var cost = unitCost(line);
          row.put("unitCost", money(cost)); row.put("cost", cost == null ? null : money(qty.multiply(cost)));
          sum = sum.add(lineAmount); rows.add(row);
        }
        var difference = total.subtract(sum);
        if (difference.abs().compareTo(new BigDecimal(rows.size()).movePointLeft(2)) > 0)
          warnings.add("订单 " + id + " 的商品计算合计与执行总额存在差额，已从末行起分配，请核对折扣或费用。");
        // Apply rounding/discount residuals from the last line without ever creating negative line amounts.
        for (int index = rows.size() - 1; index >= 0 && difference.signum() != 0; index--) {
          var row = rows.get(index); var current = (BigDecimal) row.get("amount");
          var applied = difference.signum() > 0 ? difference : difference.max(current.negate());
          row.put("amount", current.add(applied)); difference = difference.subtract(applied);
        }
        if (difference.signum() != 0) throw new IllegalArgumentException();
      } catch (IllegalArgumentException error) { warnings.add("订单 " + id + " 商品数量、单价或执行金额异常，暂未展开。"); continue; }
      for (var row : rows) {
        var lineAmount = (BigDecimal) row.get("amount");
        row.put("amount", money(lineAmount));
        row.put("profit", row.get("cost") == null ? null : money(lineAmount.subtract(new BigDecimal(row.get("cost").toString()))));
        if (matches(query, row.get("orderId"), row.get("customer"), row.get("owner"), row.get("product"), row.get("productId"), row.get("code"), row.get("model"), row.get("barcode"), row.get("status"))) matched.add(row);
      }
    }
    matched.sort(Comparator.<Map<String, Object>, String>comparing(row -> Objects.toString(row.get("date"), "")).reversed()
        .thenComparing(row -> row.get("id").toString()));
    BigDecimal quantity = BigDecimal.ZERO, amount = ZERO, cost = ZERO, profit = ZERO;
    boolean completeCosts = true;
    for (var row : matched) {
      quantity = quantity.add(new BigDecimal(row.get("qty").toString())); amount = amount.add(new BigDecimal(row.get("amount").toString()));
      if (row.get("cost") == null) completeCosts = false;
      else { cost = cost.add(new BigDecimal(row.get("cost").toString())); profit = profit.add(new BigDecimal(row.get("profit").toString())); }
    }
    if (!completeCosts) warnings.add("部分成交明细未记录成交时单位成本，成本与利润汇总暂不展示；不使用当前商品成本倒推历史利润。");
    var summary = new LinkedHashMap<String, Object>();
    summary.put("quantity", quantity.stripTrailingZeros().toPlainString()); summary.put("amount", money(amount));
    summary.put("cost", completeCosts ? money(cost) : null); summary.put("profit", completeCosts ? money(profit) : null);
    var result = page(matched, page, pageSize); result.put("summary", summary); result.put("options", options(snapshot)); result.put("warnings", new ArrayList<>(warnings));
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> receipts(int page, int pageSize, String q, String selectedMonth, String owner,
                                      String selectedCustomer, String method, Actor actor) {
    actor.require("business.read");
    validate(page, pageSize, q, owner, selectedCustomer);
    require(method.length() <= 50, "收款方式筛选无效");
    var period = selectedMonth.isEmpty() ? null : month(selectedMonth);
    String query = q.trim().toLowerCase(Locale.ROOT);
    var snapshot = snapshot();
    var warnings = new LinkedHashSet<String>();
    var matched = new ArrayList<Map<String, Object>>();
    var customerOptions = new LinkedHashMap<String, String>();
    snapshot.customers().forEach((id, customer) -> customerOptions.put(id, customer.path("name").asText()));
    var receipts = db.documents("receipts", false, 0);
    for (var receipt : receipts) {
      String id = receipt.path("customerId").asText();
      if (!id.isBlank()) customerOptions.putIfAbsent(id, customerName(receipt, id, snapshot));
    }
    var legacyKeys = new HashMap<String, String>();
    var methods = new TreeSet<String>();
    var owners = new TreeSet<String>();
    BigDecimal totalAmount = ZERO, totalTax = ZERO;
    boolean allTaxKnown = true;
    var paidCustomers = new HashSet<String>();
    for (var receipt : receipts) {
      String orderId = receipt.path("order").asText(), paymentMethod = receipt.path("method").asText();
      if (!paymentMethod.isBlank()) methods.add(paymentMethod);
      var origin = snapshot.orders().get(orderId);
      String signer = origin == null ? "" : origin.path("owner").asText();
      // Historical cash and write-offs remain visible even when their source order is inactive.
      if (!signer.isBlank()) owners.add(signer);
      String cid = receipt.path("customerId").asText();
      if (cid.isBlank() && origin != null) cid = customerId(origin, snapshot);
      if (cid.isBlank()) cid = snapshot.names().getOrDefault(receipt.path("customer").asText(), "");
      String customer = customerName(receipt, cid, snapshot);
      // The legacy-name key is a filter value only; it is never written as a real customer ID.
      String customerKey = cid;
      if (cid.isBlank()) {
        customerKey = legacyKeys.get(customer);
        if (customerKey == null) {
          int collision = 0;
          do {
            customerKey = "legacy-name:" + UUID.nameUUIDFromBytes((collision++ + ":" + customer).getBytes(StandardCharsets.UTF_8));
          } while (customerOptions.containsKey(customerKey));
          legacyKeys.put(customer, customerKey);
        }
      }
      if (!customer.isBlank() || !cid.isBlank()) customerOptions.putIfAbsent(customerKey, customer);
      if (!owner.isEmpty() && !owner.equals(signer) || !selectedCustomer.isEmpty() && !selectedCustomer.equals(customerKey)
          || !method.isEmpty() && !method.equals(paymentMethod)) continue;
      String receivedOn = text(receipt, "receivedOn");
      String time = receipt.path("time").asText();
      var groupedDate = date(receivedOn == null ? time : receivedOn);
      if (groupedDate == null) warnings.add("收款流水 " + receipt.path("id").asText() + " 的日期无效，未推算到账日期。");
      if (period != null && (groupedDate == null || !period.equals(YearMonth.from(groupedDate)))) continue;
      if (!matches(query, receipt.path("id").asText(), orderId, customer, signer, receipt.path("operator").asText(), paymentMethod)) continue;
      BigDecimal amount;
      try { amount = decimal(receipt.path("amount"), 2, true); }
      catch (IllegalArgumentException error) { warnings.add("收款流水 " + receipt.path("id").asText() + " 金额异常，暂未计入。"); continue; }
      String status = "未关联订单";
      if (origin != null) {
        var effective = snapshot.effective().getOrDefault(orderId, origin);
        try {
          var orderTotal = decimal(effective.hasNonNull("total") ? effective.path("total") : origin.path("total"), 2, false);
          var paidNode = effective.hasNonNull("paid") ? effective.path("paid") : origin.path("paid");
          var paid = paidNode.isMissingNode() || paidNode.isNull() ? ZERO : decimal(paidNode, 2, false);
          status = orderTotal.signum() > 0 && paid.compareTo(orderTotal) >= 0 ? "已回款" : paid.signum() > 0 ? "部分回款" : "待回款";
        } catch (IllegalArgumentException error) {
          status = "待核对"; warnings.add("订单 " + orderId + " 的当前执行金额异常，回款状态待核对。");
        }
      }
      BigDecimal tax = null;
      String taxRate = null;
      if (receipt.hasNonNull("tax")) try { tax = decimal(receipt.path("tax"), 2, true); }
      catch (IllegalArgumentException ignored) { warnings.add("部分流水已登记税额格式异常，请核对。"); }
      if (receipt.hasNonNull("taxRate")) {
        var rate = receipt.path("taxRate");
        if ((rate.isTextual() || rate.isNumber()) && rate.asText().length() <= 50) taxRate = rate.asText();
        else warnings.add("部分流水已登记税率格式异常，请核对。");
      }
      var row = new LinkedHashMap<String, Object>();
      row.put("id", receipt.path("id").asText()); row.put("orderId", orderId); row.put("customerId", cid);
      row.put("customer", customer); row.put("owner", signer); row.put("type", receipt.path("type").asText());
      row.put("amount", money(amount)); row.put("method", paymentMethod); row.put("receivedOn", receivedOn); row.put("time", time);
      row.put("operator", receipt.path("operator").asText());
      row.put("note", receipt.path("note").asText());
      var products = BusinessService.receiptItems(receipt, origin, snapshot.products());
      row.put("items", products.path("items")); row.put("itemsSource", products.path("itemsSource").asText());
      var orderDay = origin == null ? null : orderDate(origin, warnings);
      row.put("orderDate", orderDay == null ? null : orderDay.toString()); row.put("status", status);
      row.put("taxRate", taxRate); row.put("tax", money(tax));
      row.put("dateBasis", receivedOn == null ? "time" : "receivedOn");
      matched.add(row); totalAmount = totalAmount.add(amount);
      if (tax == null) allTaxKnown = false; else totalTax = totalTax.add(tax);
      if (!customer.isBlank() || !cid.isBlank()) paidCustomers.add(customerKey);
      if (receivedOn == null) warnings.add("未登记实际到账日期的流水按登记日期（北京时间）归月。");
      if ("预收款核销".equals(paymentMethod)) warnings.add("预收款核销属于订单结算，不代表新增现金流入。");
    }
    matched.sort(Comparator.<Map<String, Object>, String>comparing(row -> Objects.toString(row.get("time"), "")).reversed()
        .thenComparing(row -> row.get("id").toString()));
    if (!allTaxKnown || matched.isEmpty()) warnings.add("税率与税额仅展示流水中实际登记的数据，缺失时不按固定税率推算。");
    var summary = new LinkedHashMap<String, Object>();
    summary.put("amount", money(totalAmount)); summary.put("tax", allTaxKnown && !matched.isEmpty() ? money(totalTax) : null);
    summary.put("customers", paidCustomers.size());
    var options = new LinkedHashMap<String, Object>(); options.put("owners", owners); options.put("methods", methods);
    options.put("customers", customerOptions.entrySet().stream().map(e -> Map.of("id", e.getKey(), "name", e.getValue())).toList());
    var result = page(matched, page, pageSize); result.put("summary", summary); result.put("options", options); result.put("warnings", new ArrayList<>(warnings));
    return result;
  }

  private TreeSet<String> owners(JsonNode state, List<Map<String, Object>> targets) {
    var owners = new TreeSet<String>();
    for (var row : db.rows(OWNERS)) if (row.get("owner") != null && !row.get("owner").toString().isBlank()) owners.add(row.get("owner").toString());
    for (var order : state.path("orders"))
      if (activeOrder(order) && !order.path("owner").asText().isBlank()) owners.add(order.path("owner").asText());
    for (var row : db.rows("SELECT DISTINCT owner FROM sales_targets ORDER BY owner"))
      if (row.get("owner") != null && !row.get("owner").toString().isBlank()) owners.add(row.get("owner").toString());
    for (var target : targets)
      if (target.get("owner") != null && !target.get("owner").toString().isBlank()) owners.add(target.get("owner").toString());
    return owners;
  }

  private static Map<String, Object> target(String month, String owner, Map<String, Object> stored) {
    var row = new LinkedHashMap<String, Object>();
    row.put("month", month); row.put("owner", owner);
    row.put("amount", stored == null || stored.get("amount") == null ? null : money(new BigDecimal(stored.get("amount").toString())));
    row.put("revision", stored == null ? 0L : ((Number) stored.get("revision")).longValue());
    row.put("updatedAt", stored == null ? null : stored.get("updated_at")); row.put("updatedBy", stored == null ? null : stored.get("updated_by"));
    return row;
  }

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public Map<String, Object> targets(String selectedMonth, Actor actor) {
    actor.require("business.read");
    String key = month(selectedMonth).toString();
    var scope = scopes.snapshot();
    var stored = db.rows(TARGETS, key);
    var owners = owners(db.state(false), stored);
    var configured = new HashMap<String, Map<String, Object>>();
    stored.forEach(row -> configured.put(row.get("owner").toString(), row));
    var report = dashboard.salesReport(key, "", "", scope);
    var warnings = new LinkedHashSet<String>((Collection<String>) report.get("warnings"));
    var achieved = new HashMap<String, BigDecimal>(); var settled = new HashMap<String, BigDecimal>();
    for (var order : (List<Map<String, Object>>) report.get("orders")) {
      String owner = Objects.toString(order.get("owner"), "");
      if (owner.isBlank()) {
        warnings.add("部分已批准订单缺少签单人，仍计入公司业绩，未分配到个人目标。");
        continue;
      }
      owners.add(owner);
      achieved.merge(owner, new BigDecimal(order.get("total").toString()), BigDecimal::add);
      settled.merge(owner, new BigDecimal(order.get("paid").toString()), BigDecimal::add);
    }
    var rows = new ArrayList<Map<String, Object>>();
    owners.removeIf(owner -> !scope.includes(owner));
    for (String owner : owners) {
      var row = target(key, owner, configured.get(owner));
      row.putAll(scope.department(owner));
      var orderAmount = achieved.getOrDefault(owner, ZERO); var settledAmount = settled.getOrDefault(owner, ZERO);
      var goal = row.get("amount") == null ? null : new BigDecimal(row.get("amount").toString());
      row.put("orderAmount", money(orderAmount)); row.put("settledAmount", money(settledAmount));
      row.put("remaining", goal == null ? null : money(goal.subtract(orderAmount).max(ZERO)));
      row.put("progress", goal == null || goal.signum() == 0 ? null : orderAmount.multiply(new BigDecimal("100")).divide(goal, 2, RoundingMode.HALF_UP));
      rows.add(row);
    }
    return Map.of("month", key, "rows", rows, "options", Map.of("owners", owners, "departments", scope.selectableDepartments()), "scope", scope.metadata(), "warnings", new ArrayList<>(warnings));
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> saveTarget(JsonNode body, Actor actor) {
    actor.require("dashboard.targets.manage");
    String key = month(body.path("month").asText()).toString(), owner = body.path("owner").asText();
    require(!owner.isBlank() && owner.length() <= 191, "请选择有效签单人");
    require(owners(db.state(false), db.rows(TARGETS, key)).contains(owner), "签单人不存在，请刷新后从已有签单人中选择");
    String raw = body.path("amount").asText();
    require(raw.matches("[0-9]{1,12}(?:\\.[0-9]{1,2})?"), "目标金额须为非负数，最多12位整数和两位小数");
    var amount = new BigDecimal(raw).setScale(2);
    var version = body.path("revision");
    require(version.isIntegralNumber() && version.canConvertToLong() && version.asLong() >= 0 && version.asLong() < Long.MAX_VALUE,
        "请提供有效目标版本，刷新后重试");
    long revision = version.asLong();
    var scope = scopes.snapshotForWrite();
    if (!scope.includes(owner)) throw new ApiException(409, "该人员不在当前目标统计范围，请联系管理员调整部门范围");
    db.jdbc().update("INSERT IGNORE INTO sales_targets(month_key,owner,amount,revision,updated_at,updated_by) VALUES(?,?,NULL,0,NULL,NULL)", key, owner);
    var locked = db.rows(TARGETS + " AND owner=? FOR UPDATE", key, owner);
    var before = target(key, owner, locked.isEmpty() ? null : locked.getFirst());
    if (((Number) before.get("revision")).longValue() != revision) throw new ApiException(409, "销售业绩目标已被其他人更新，请刷新后重试");
    int changed = db.jdbc().update("UPDATE sales_targets SET amount=?,revision=revision+1,updated_at=?,updated_by=? WHERE month_key=? AND owner=? AND revision=?",
        amount, CrmRepository.now(), actor.name(), key, owner, revision);
    if (changed != 1) throw new ApiException(409, "销售业绩目标已被其他人更新，请刷新后重试");
    var saved = db.rows(TARGETS + " AND owner=?", key, owner);
    var after = target(key, owner, saved.getFirst());
    var detail = db.object().put("type", "SALES_TARGET_CHANGED").put("month", key).put("owner", owner);
    detail.set("before", db.tree(before)); detail.set("after", db.tree(after));
    db.audit(actor.name(), "设置销售业绩目标 " + key + " / " + owner, detail);
    return after;
  }
}
