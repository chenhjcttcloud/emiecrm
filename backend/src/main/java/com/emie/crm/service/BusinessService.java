package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessService {
  static final String CANCELED_ORDER = "已取消";
  private static final Set<String> DELETABLE_ORDER_STATUSES = Set.of("草稿", "审批中", CANCELED_ORDER);
  private static final Set<String> STOPPED_APPROVAL_STATUSES = Set.of("REJECTED", "CANCELED", "DELETED");
  private static final Set<String> APPROVAL_TYPES = Set.of("销售订单", "海外提货单", "预收款订单");
  private final CrmRepository db;
  private final AssetService assets;
  @Value("${CRM_FEISHU_RECEIPT_APPROVAL_CODE:}") private String feishuReceiptApprovalCode = "";
  private static final List<String> RECEIPT_APPROVAL_FIELDS =
      List.of("id", "type", "customerId", "customer", "owner", "items", "total", "status", "paid", "deletedAt");
  private static final String ACTIVE_RECEIPT_APPROVALS = """
      SELECT payload FROM feishu_receipt_approvals
      WHERE status IN ('QUEUED','SUBMITTING','UNKNOWN','PENDING')
         OR (status='APPROVED' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.booked')),'false')<>'true')
      """;
  private static final List<String> APPROVAL_FIELDS = List.of("status", "type", "owner", "customerId", "customer", "items", "total", "approvalRequestId", "approvalDraft");

  public BusinessService(CrmRepository db, AssetService assets) {
    this.db = db;
    this.assets = assets;
  }

  public JsonNode state() {
    var state = db.state(false);
    if (!state.isNull()) normalizeOrders(state, state);
    return state;
  }

  /** A refresh only needs the document again when its saved revision has changed. */
  public JsonNode state(Long knownRevision) {
    if (knownRevision != null) {
      require(knownRevision >= 0, "业务数据版本无效");
      var rows = db.rows("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.revision')) AS revision"
          + " FROM state WHERE id=1 AND payload IS NOT NULL");
      if (!rows.isEmpty()) {
        Object value = rows.getFirst().get("revision");
        if (value != null && value.toString().equals(knownRevision.toString()))
          return db.object().put("unchanged", true).put("revision", knownRevision);
      }
    }
    return state();
  }

  private BigDecimal number(JsonNode value, boolean optional) {
    if (value.isMissingNode() || value.isNull()) {
      if (optional) return BigDecimal.ZERO;
      throw new ApiException("订单金额无效");
    }
    try {
      String text = value.asText();
      require(text.length() <= 40 && text.matches("-?[0-9]+(?:\\.[0-9]+)?"), "金额或数量格式无效");
      return new BigDecimal(text);
    } catch (Exception e) {
      throw new ApiException("金额必须为有效数字");
    }
  }

  private boolean same(JsonNode a, JsonNode b) {
    return a.equals(b)
        || (a.isNumber() && b.isNumber() && a.decimalValue().compareTo(b.decimalValue()) == 0);
  }

  private boolean sameRecord(JsonNode left, JsonNode right) {
    if (!left.isObject() || !right.isObject() || left.size() != right.size()) return false;
    for (var field : left.properties())
      if (!same(field.getValue(), right.path(field.getKey()))) return false;
    return true;
  }

  private void validState(JsonNode n) {
    require(
        n.isObject()
            && n.path("orders").isArray()
            && n.path("customers").isArray()
            && n.path("products").isArray(),
        "数据结构无效");
    for (String key : List.of("orders", "customers", "products")) {
      var ids = new HashSet<String>();
      for (var entry : n.path(key))
        require(
            entry.isObject()
                && !entry.path("id").asText().isBlank()
                && ids.add(entry.path("id").asText()),
            "记录ID无效或重复");
    }
    CustomerExternalLinks.validate(n.path("customers"));
    LegacyCustomerData.validate(n.path("customers"));
  }

  private Map<String, JsonNode> index(JsonNode nodes) {
    var result = new HashMap<String, JsonNode>();
    for (var node : nodes) result.put(node.path("id").asText(), node);
    return result;
  }

  private Map<String, JsonNode> customersByName(JsonNode state) {
    var result = new HashMap<String, JsonNode>();
    var ambiguous = new HashSet<String>();
    for (var customer : state.path("customers")) {
      String name = customer.path("name").asText().trim();
      require(!name.isEmpty() && name.equals(customer.path("name").asText()), "客户名称不能为空或包含首尾空格");
      if (result.put(name, customer) != null) ambiguous.add(name);
    }
    ambiguous.forEach(result::remove);
    return result;
  }

  private void normalizeOrders(JsonNode state, JsonNode legacy) {
    var names = customersByName(legacy);
    var customers = index(state.path("customers"));
    var currentNames = customersByName(state);
    for (var order : state.path("orders")) {
      if (canceledOrder(order) || deletedOrder(order)) continue;
      String id = order.path("customerId").asText();
      if (id.isBlank()) {
        var customer = names.get(order.path("customer").asText());
        if (customer == null) customer = currentNames.get(order.path("customer").asText());
        if (customer == null) continue; // Preserve unresolved legacy records; never guess their owner.
        id = customer.path("id").asText();
      }
      var customer = customers.get(id);
      if (customer == null) continue;
      ((ObjectNode) order).put("customerId", id).put("customer", customer.path("name").asText());
    }
  }

  private record References(Map<String, JsonNode> orders, Map<String, JsonNode> customers, Map<String, JsonNode> names, Map<String, JsonNode> products) {}

  private References references(JsonNode state) {
    return new References(index(state.path("orders")), index(state.path("customers")), customersByName(state), index(state.path("products")));
  }

  private JsonNode normalizeReceipt(JsonNode receipt, References refs) {
    ObjectNode result = receipt.deepCopy();
    String id = result.path("customerId").asText();
    if (id.isBlank()) {
      var order = refs.orders().get(result.path("order").asText());
      var customer = refs.names().get(result.path("customer").asText());
      id = order != null ? order.path("customerId").asText() : "";
      if (id.isBlank() && customer != null) id = customer.path("id").asText();
      if (id.isBlank()) return result;
    }
    var customer = refs.customers().get(id);
    if (customer == null) return result;
    result.put("customerId", id).put("customer", customer.path("name").asText());
    return result;
  }

  private boolean belongsTo(JsonNode record, JsonNode customer) {
    String id = record.path("customerId").asText();
    return id.equals(customer.path("id").asText())
        || (id.isBlank() && record.path("customer").asText().equals(customer.path("name").asText()));
  }

  private BigDecimal money(JsonNode value, boolean optional) {
    BigDecimal amount = number(value, optional);
    require(amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(new BigDecimal("999999999999.99")) <= 0,
        "金额须为非负数，最多两位小数且不超过999999999999.99");
    return amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  private BigDecimal receiptAmount(JsonNode receipt) {
    if (!receipt.path("type").asText().equals("订单变更")) return money(receipt.path("amount"), false);
    var amount = number(receipt.path("amount"), false);
    require(amount.signum() != 0 && amount.stripTrailingZeros().scale() <= 2
        && amount.abs().compareTo(new BigDecimal("999999999999.99")) <= 0
        && !receipt.path("amendmentId").asText().isBlank()
        && !receipt.path("method").asText().equals("预收款核销"), "订单变更差额流水无效");
    return amount;
  }

  private boolean coreChanged(JsonNode order, JsonNode prior) {
    for (String key : List.of("total", "customerId", "type", "items"))
      if (!same(order.path(key), prior.path(key))) return true;
    return !OrderWarehouse.same(order, prior) || !JushuitanCreditPushPolicy.samePlan(order, prior);
  }

  private void validateTotal(JsonNode order, JsonNode products) {
    boolean prepayment = "预收款订单".equals(order.path("type").asText());
    if (prepayment && (order.path("items").isMissingNode() || order.path("items").isNull())) return;
    require(order.path("items").isArray() && (prepayment || !order.path("items").isEmpty()), "销售订单须填写产品明细");
    var byId = index(products);
    BigDecimal total = BigDecimal.ZERO;
    for (var item : order.path("items")) {
      require(byId.containsKey(item.path("product").asText()), "订单产品不存在");
      BigDecimal quantity = number(item.path("qty"), false);
      require(quantity.signum() > 0 && quantity.compareTo(new BigDecimal("1000000000")) <= 0
          && quantity.stripTrailingZeros().scale() <= 6, "产品数量须大于零，最多六位小数且不超过十亿");
      total = total.add(quantity.multiply(money(item.path("price"), false)));
    }
    if (!prepayment) require(total.setScale(2, RoundingMode.HALF_UP).compareTo(money(order.path("total"), false)) == 0,
        "订单总额与产品明细合计不一致");
  }

  private static ArrayNode productItems(JsonNode order, Map<String, JsonNode> products) {
    var items = JsonNodeFactory.instance.arrayNode();
    if (order == null) return items;
    for (var line : order.path("items")) {
      var product = products.getOrDefault(line.path("product").asText(), MissingNode.getInstance());
      String name = product.path("name").asText();
      if (name.isBlank()) name = line.path("name").asText(line.path("product").asText());
      String sku = product.path("jstSku").asText();
      if (sku.isBlank()) sku = product.path("code").asText();
      if (sku.isBlank()) sku = line.path("sku").asText(line.path("code").asText());
      String model = product.path("model").asText();
      if (model.isBlank()) model = line.path("model").asText();
      var item = items.addObject().put("product", line.path("product").asText()).put("name", name).put("sku", sku).put("model", model);
      item.set("qty", line.path("qty").deepCopy());
      item.set("price", line.path("price").deepCopy());
    }
    return items;
  }

  /** Read-only display enrichment: never persist a legacy current-order fallback as a receipt snapshot. */
  static ObjectNode receiptItems(JsonNode receipt, JsonNode order, Map<String, JsonNode> products) {
    ObjectNode result = receipt.deepCopy();
    String sourceKind = receipt.path("itemsSource").asText();
    boolean derived = Set.of("order", "unknown").contains(sourceKind);
    if (receipt.path("items").isArray() && (!derived || order == null)) {
      var items = result.putArray("items");
      for (var source : receipt.path("items")) {
        var item = items.addObject();
        for (String field : List.of("product", "name", "sku", "model", "qty", "price"))
          if (source.has(field)) item.set(field, source.path(field).deepCopy());
      }
      result.put("itemsSource", derived ? "unknown" : "snapshot");
    } else {
      result.set("items", productItems(order, products));
      result.put("itemsSource", order == null ? "unknown" : "order");
    }
    return result;
  }

  @Transactional(readOnly = true)
  public List<JsonNode> receipts() {
    var state = state();
    var refs = references(state);
    return db.documents("receipts", true, 0).stream()
        .<JsonNode>map(r -> receiptItems(normalizeReceipt(r, refs), refs.orders().get(r.path("order").asText()), refs.products())).toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> receiptsPage(int page, int pageSize, String query, String method) {
    require(page >= 1 && pageSize >= 1 && pageSize <= 100 && query.length() <= 200 && method.length() <= 50,
        "分页或筛选参数无效");
    var refs = references(state());
    String customer = "COALESCE(c.name,JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.customer')))";
    String from = " FROM receipts r LEFT JOIN (SELECT j.id,j.name,COUNT(*) OVER (PARTITION BY j.name) AS name_count"
        + " FROM state s JOIN JSON_TABLE(COALESCE(s.payload,'{\"customers\":[]}'),"
        + "'$.customers[*]' COLUMNS(id VARCHAR(191) PATH '$.id',name VARCHAR(191) PATH '$.name')) j ON TRUE WHERE s.id=1) c ON "
        + "(c.id=JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.customerId')) OR "
        + "(JSON_EXTRACT(r.payload,'$.customerId') IS NULL AND c.name_count=1 AND c.name=JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.customer'))))";
    String where = " WHERE (?='' OR JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.method'))=?)"
        + " AND (?='' OR LOCATE(LOWER(?),LOWER(CONCAT_WS(' ',"
        + "JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.order'))," + customer + ","
        + "JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.operator')),JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.method')))))>0)";
    Object[] args = {method, method, query, query};
    var summary = db.rows("SELECT COUNT(*) AS total,COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.amount')) AS DECIMAL(20,2))),0) AS amount,"
        + "COUNT(DISTINCT COALESCE(c.id,JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.customerId'))," + customer + ")) AS customers" + from + where, args).getFirst();
    long total = ((Number) summary.get("total")).longValue();
    int currentPage = (int) Math.min(page, Math.max(1, (total + pageSize - 1) / pageSize));
    var params = new ArrayList<Object>(Arrays.asList(args));
    params.add(pageSize); params.add((long) (currentPage - 1) * pageSize);
    var rows = db.rows("SELECT r.payload," + customer + " AS customer,c.id AS customerId" + from + where
        + " ORDER BY r.seq DESC LIMIT ? OFFSET ?", params.toArray()).stream().map(row -> {
          ObjectNode receipt = (ObjectNode) db.parse(row.get("payload").toString());
          receipt.put("customer", Objects.toString(row.get("customer"), ""));
          if (row.get("customerId") != null) receipt.put("customerId", row.get("customerId").toString());
          return receiptItems(receipt, refs.orders().get(receipt.path("order").asText()), refs.products());
        }).toList();
    var methods = db.rows("SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.method')) AS method FROM receipts ORDER BY method")
        .stream().map(row -> row.get("method")).filter(Objects::nonNull).toList();
    return Map.of("rows", rows, "total", total, "page", currentPage, "amount", summary.get("amount"),
        "customers", summary.get("customers"), "methods", methods);
  }

  /** Only the receipt approval service may call this after a verified, persisted APPROVED instance. */
  @Transactional
  public JsonNode applyApprovedReceipt(JsonNode batch) {
    require(batch.isObject() && batch.path("id").isTextual() && !batch.path("id").asText().isBlank()
        && batch.path("id").asText().length() <= 64 && !batch.path("instanceCode").asText().isBlank(), "收款审批记录无效");
    require(!batch.path("operator").asText().isBlank() && !batch.path("operatorRole").asText().isBlank(), "收款审批操作人缺失");
    try {
      require(batch.path("receiptDate").asText().matches("\\d{4}-\\d{2}-\\d{2}"), "收款日期无效");
      LocalDate.parse(batch.path("receiptDate").asText());
    } catch (java.time.format.DateTimeParseException invalidDate) {
      throw new ApiException("收款日期无效");
    }
    require(!batch.path("receiptCategory").asText().isBlank(), "收款类别缺失");
    var current = db.state(true);
    var persisted = db.rows("SELECT payload FROM feishu_receipt_approvals WHERE id=? FOR UPDATE", batch.path("id").asText());
    require(!persisted.isEmpty(), "收款审批记录不存在");
    var verified = db.parse(persisted.getFirst().get("payload").toString());
    require("APPROVED".equals(verified.path("status").asText()) && verified.equals(batch), "收款审批尚未通过或与入账记录不一致");
    normalizeOrders(current, current);
    ObjectNode next = current.deepCopy();
    var orders = index(next.path("orders"));
    var allocations = batch.path("allocations");
    require(allocations.isArray() && !allocations.isEmpty(), "收款分配明细无效");
    var ids = new HashSet<String>();
    BigDecimal allocated = BigDecimal.ZERO;
    String timestamp = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    for (var allocation : allocations) {
      String orderId = allocation.path("orderId").asText();
      var order = orders.get(orderId);
      var snapshot = allocation.path("orderSnapshot");
      require(ids.add(orderId) && order != null && snapshot.isObject(), "收款订单缺失或重复");
      require("已批准".equals(order.path("status").asText()) && !deletedOrder(order), "收款订单须已批准且未删除");
      for (String field : RECEIPT_APPROVAL_FIELDS)
        require(same(order.path(field), snapshot.path(field)), "收款审批提交后订单内容或已收金额已变化，请核对后处理");
      require(order.path("customerId").equals(batch.path("customerId"))
          && order.path("customer").equals(batch.path("customer")), "收款客户与审批记录不一致");
      var execution = receiptExecutionView(db, order);
      require(allocation.path("executionSnapshot").isObject()
          && sameReceiptExecution(execution, allocation.path("executionSnapshot")), "订单执行信息已变化，请核对审批后再入账");
      require(same(execution.path("paid"), order.path("paid"))
          && same(execution.path("total"), order.path("total")), "订单执行金额已变化，请先核对最新收款数据");
      var amount = money(allocation.path("amount"), false);
      var paid = money(order.path("paid"), true);
      require(amount.signum() > 0 && paid.add(amount).compareTo(money(order.path("total"), false)) <= 0,
          "收款分配须大于零且不能超过订单未收金额");
      require(allocation.path("items").isArray(), "收款产品快照缺失");
      ((ObjectNode) order).put("paid", paid.add(amount)).put("method", batch.path("method").asText()).put("updated", timestamp);
      allocated = allocated.add(amount);
    }
    require(allocated.compareTo(money(batch.path("amount"), false)) == 0, "收款审批金额与分配合计不一致");
    require(db.documents("receipts", false, 0).stream()
        .noneMatch(receipt -> batch.path("id").equals(receipt.path("approvalId"))), "该收款审批已入账，请刷新后核对");
    var actor = new Actor(batch.path("operator").asText(), batch.path("operatorRole").asText(),
        Set.of("business.read", "business.write", "payment.write"));
    var body = db.object().put("revision", current.path("revision").asLong());
    body.set("data", next);
    body.set("receiptVerification", db.object().put("amount", allocated).put("customerId", batch.path("customerId").asText())
        .put("customer", batch.path("customer").asText()).put("method", batch.path("method").asText()));
    ((ObjectNode) body.path("receiptVerification")).put("receivedOn", batch.path("receiptDate").asText());
    return save(body, actor, false, "", batch);
  }

  private boolean sameReceiptExecution(JsonNode current, JsonNode snapshot) {
    for (String field : RECEIPT_APPROVAL_FIELDS)
      if (!same(current.path(field), snapshot.path(field))) return false;
    return true;
  }

  /** Read-only execution money/products used to freeze the exact receipt proposal. */
  static ObjectNode receiptExecutionView(CrmRepository db, JsonNode order) {
    ObjectNode view = order.deepCopy();
    for (var row : db.rows("SELECT payload FROM jst_shipments WHERE order_id=?", order.path("id").asText())) {
      if (row.get("payload") == null) continue;
      var record = db.parse(row.get("payload").toString());
      if (record == null || !record.path("effectiveOrder").isObject()) continue;
      var effective = record.path("effectiveOrder");
      for (String key : List.of("total", "paid", "items"))
        if (effective.hasNonNull(key) && !(key.equals("paid") && record.path("creditFulfillment").asBoolean()))
          view.set(key, effective.path(key).deepCopy());
    }
    if (!view.hasNonNull("paid")) view.put("paid", 0);
    return view;
  }

  @Transactional
  public JsonNode save(JsonNode body, Actor actor) {
    return save(body, actor, false);
  }

  /** Only the audited offline migration service may add historical credit and provenance. */
  JsonNode saveLegacyMigration(JsonNode body, Actor actor) {
    actor.require("system.manage");
    actor.require("payment.write");
    return save(body, actor, true);
  }

  /** Package-local entry used only while the approval service owns the encompassing transaction. */
  JsonNode saveForFeishuApproval(JsonNode body, Actor actor, String orderId) {
    return save(body, actor, false, orderId);
  }

  private JsonNode save(JsonNode body, Actor actor, boolean migration) {
    return save(body, actor, migration, "");
  }

  private JsonNode save(JsonNode body, Actor actor, boolean migration, String submittingOrderId) {
    return save(body, actor, migration, submittingOrderId, null);
  }

  private JsonNode save(JsonNode body, Actor actor, boolean migration, String submittingOrderId, JsonNode approvedReceipt) {
    actor.require("business.read");
    actor.require("business.write");
    var old = db.state(true);
    if (!old.isNull() && !same(body.path("revision"), old.path("revision")))
      throw new ApiException(409, "数据已由其他操作更新，请刷新后重试");
    var input = body.path("data");
    validState(input);
    ObjectNode next = input.deepCopy();
    SettlementOrderNumbers.protect(old, next, false);
    require(next.path("products").equals(old.path("products")) || (old.isNull() && next.path("products").isEmpty()),
        "商品资料由聚水潭管理，请使用同步商品，不能在CRM新增、编辑或删除");
    // Catalog ownership and synchronization metadata cannot be replaced through a state save.
    for (String field : List.of("productSource", "productsSyncedAt")) {
      next.remove(field);
      if (old.has(field)) next.set(field, old.path(field));
    }
    customersByName(next);
    var oldCustomers = index(old.path("customers"));
    var nextCustomers = index(next.path("customers"));
    for (var c : next.path("customers")) {
      var prior = oldCustomers.get(c.path("id").asText());
      for (String field : LegacyCustomerData.PROTECTED_FIELDS) {
        var before = prior == null ? MissingNode.getInstance() : prior.path(field);
        if (migration && before.isMissingNode()) continue;
        require(same(before, c.path(field)), "期初余额及旧系统来源只能通过专用迁移写入，不能修改或移除");
      }
    }
    var nameCounts = new HashMap<String, Integer>();
    for (var c : next.path("customers")) nameCounts.merge(c.path("name").asText(), 1, Integer::sum);
    for (var c : next.path("customers")) {
      var prior = oldCustomers.get(c.path("id").asText());
      if (prior == null || !same(prior.path("name"), c.path("name")))
        require(nameCounts.get(c.path("name").asText()) == 1, "客户名称重复，请使用可区分的名称");
    }
    if (!old.isNull()) normalizeOrders(old, old);
    normalizeOrders(next, old.isNull() ? next : old);
    var oldOrders = index(old.path("orders"));
    var nextOrders = index(next.path("orders"));
    nextOrders.values().forEach(OrderWarehouse::validateDraft);
    nextOrders.values().forEach(JushuitanCreditPushPolicy::validateDraft);
    protectCanceledOrders(oldOrders, nextOrders, false);
    protectDeletedOrders(oldOrders, nextOrders, false);
    protectFeishuOrders(oldOrders, nextOrders, submittingOrderId, true);
    protectReceiptApprovalOrders(oldOrders, nextOrders, approvedReceipt == null ? "" : approvedReceipt.path("id").asText());
    protectSubmittedOrders(nextOrders);
    for (var entry : nextOrders.entrySet()) {
      String id = entry.getKey();
      var prior = oldOrders.get(id);
      if (prior == null || "预收款订单".equals(prior.path("type").asText())
          || !"预收款订单".equals(entry.getValue().path("type").asText())) continue;
      require(db.rows("""
          SELECT order_id FROM jst_document_sales WHERE order_id=?
          UNION SELECT order_id FROM jst_document_purchases WHERE order_id=? LIMIT 1
          """, id, id).isEmpty(), "改为预收款订单前，请先解除聚水潭单据关联");
    }
    var oldRefs = references(old);
    var nextRefs = references(next);
    var historicalReceipts = new ArrayList<JsonNode>();
    var migratedReceipts = new ArrayList<JsonNode>();
    var receiptOriginals = new HashMap<String, JsonNode>();
    for (var receipt : db.documents("receipts", false, 0)) {
      receiptOriginals.put(receipt.path("id").asText(), receipt.deepCopy());
      var normalized = normalizeReceipt(receipt, oldRefs);
      if (normalized.path("customerId").asText().isBlank()) {
        var priorOrder = oldOrders.get(receipt.path("order").asText());
        var nextOrder = nextOrders.get(receipt.path("order").asText());
        if (priorOrder != null && nextOrder != null && priorOrder.path("customerId").asText().isBlank()
            && !nextOrder.path("customerId").asText().isBlank()) normalized = normalizeReceipt(receipt, nextRefs);
      }
      historicalReceipts.add(normalized);
      if (receipt.path("customerId").asText().isBlank() && !normalized.path("customerId").asText().isBlank()) migratedReceipts.add(normalized);
    }
    if (!old.isNull())
      for (String key : List.of("orders"))
        for (var entry : old.path(key))
          require(
              nextOrders.containsKey(entry.path("id").asText()),
              "不能移除历史" + (key.equals("orders") ? "订单" : "客户"));
    if (!old.isNull()) {
      var nextProducts = index(next.path("products"));
      var usedProducts = new HashSet<String>();
      for (var order : next.path("orders"))
        for (var item : order.path("items")) usedProducts.add(item.path("product").asText());
      for (var row : db.rows("SELECT payload FROM jst_shipments"))
        collectShipmentProducts(db.parse(row.get("payload").toString()), usedProducts);
      for (var product : old.path("products")) {
        String id = product.path("id").asText();
        require(nextProducts.containsKey(id) || !usedProducts.contains(id), "该商品已被订单使用，不能删除");
      }
    }
    if (!old.isNull()) {
      for (var customer : old.path("customers")) {
        if (nextCustomers.containsKey(customer.path("id").asText())) continue;
        require(number(customer.path("balance"), true).signum() == 0, "该客户有预收款余额，不能删除");
        for (var order : old.path("orders"))
          require(!belongsTo(order, customer), "该客户有关联订单，不能删除");
        for (var order : next.path("orders"))
          require(!belongsTo(order, customer), "该客户有关联订单，不能删除");
        for (var receipt : historicalReceipts)
          require(!belongsTo(receipt, customer), "该客户有关联收款记录，不能删除");
      }
    }
    List<JsonNode> receipts = new ArrayList<>();
    for (var entry : next.path("orders")) {
      var prior = oldOrders.getOrDefault(entry.path("id").asText(), NullNode.instance);
      boolean settlement = JushuitanDocumentService.isSettlement(entry);
      require(settlement == JushuitanDocumentService.isSettlement(prior),
          "请通过聚水潭结算补录入口创建结算单");
      if (settlement)
        require(!coreChanged(entry, prior) && sameRecord(entry.path("settlement"), prior.path("settlement")),
            "结算单的客户、结算期间和确认金额已锁定，请通过关联单据核对明细");
      require(List.of("草稿", "审批中", "已批准", CANCELED_ORDER).contains(entry.path("status").asText()), "订单状态无效");
      require(List.of("销售订单", "预收款订单", "海外提货单").contains(entry.path("type").asText()), "订单类型无效");
      boolean changed = coreChanged(entry, prior);
      boolean resolved = nextCustomers.containsKey(entry.path("customerId").asText());
      if (!resolved) require(!prior.isNull() && !changed
          && same(entry.path("customer"), prior.path("customer"))
          && same(entry.path("paid"), prior.path("paid"))
          && same(entry.path("status"), prior.path("status")),
          "历史订单客户关联待确认，请编辑订单并重新选择客户");
      boolean lineItemsChanged = prior.isNull();
      for (String key : List.of("total", "type", "items"))
        lineItemsChanged |= !same(entry.path(key), prior.path(key));
      if (lineItemsChanged) {
        validateTotal(entry, next.path("products"));
        var catalog = index(next.path("products"));
        for (var line : entry.path("items")) {
          var product = catalog.get(line.path("product").asText());
          require(product != null && product.path("source").asText().equals("jushuitan")
              && product.path("enabled").asBoolean(), "新建或修改商品明细前，请从聚水潭同步并选择已启用商品");
        }
      }
      if (prior.path("status").asText().equals("已批准")
          && (changed || !same(entry.path("status"), prior.path("status")))) actor.require("order.approve");
      if (entry.path("status").asText().equals("已批准")
          && !prior.path("status").asText().equals("已批准")) actor.require("order.approve");
      BigDecimal total = money(entry.path("total"), false),
          paid = money(entry.path("paid"), true),
          before = number(prior.path("paid"), true),
          delta = paid.subtract(before);
      if (settlement && before.signum() > 0)
        require(entry.path("status").asText().equals("已批准"), "已收款结算单须保留审批状态");
      require(
          total.signum() >= 0
              && paid.signum() >= 0
              && delta.signum() >= 0
              && paid.compareTo(total) <= 0,
          "订单金额或收款金额无效");
      if (before.signum() > 0)
        for (String key : List.of("total", "customerId", "type", "items")) {
          if (key.equals("customerId") && prior.path(key).asText().isBlank() && resolved)
            actor.require("payment.write"); // Explicitly repair a legacy association; never infer ambiguous ownership.
          else require(same(entry.path(key), prior.path(key)), "已有收款的订单不可更改金额、产品、客户或类型");
        }
      if (delta.signum() > 0) {
        actor.require("payment.write");
        require(feishuReceiptApprovalCode.isBlank() || approvedReceipt != null, "收款须通过飞书审批后入账");
        require(resolved, "请先为订单选择客户");
        for (var historical : historicalReceipts)
          require(!historical.path("customerId").asText().isBlank()
              || !historical.path("customer").asText().equals(entry.path("customer").asText()),
              "该客户有待确认归属的历史收款，请先核对流水");
        require(entry.path("status").asText().equals("已批准"), "订单需先批准");
        String method = entry.path("method").asText("银行转账");
        if (settlement) require(!method.equals("预收款核销"), "结算补录须登记实际到账的收款方式");
        require(method.equals("预收款核销") || !db.rows("SELECT id FROM payment_methods WHERE name=? AND status='启用'", method).isEmpty(), "请选择已启用的收款方式");
        require(
            !(entry.path("type").asText().equals("预收款订单") && method.equals("预收款核销")),
            "预收款订单不能使用核销方式");
        var r = db.object();
        r.put("id", approvedReceipt == null ? UUID.randomUUID().toString()
            : UUID.nameUUIDFromBytes((approvedReceipt.path("id").asText()+":"+entry.path("id").asText())
                .getBytes(StandardCharsets.UTF_8)).toString());
        r.put("order", entry.path("id").asText());
        r.put("customer", entry.path("customer").asText());
        r.put("customerId", entry.path("customerId").asText());
        r.put("amount", delta.setScale(2, RoundingMode.HALF_UP));
        r.put("method", method);
        r.put("type", entry.path("type").asText());
        r.put("time", CrmRepository.now());
        if (settlement) r.put("receivedOn", JushuitanSettlementService.receivedOn(
            body.path("receiptVerification").path("receivedOn").asText(), entry));
        r.put("operator", actor.name());
        r.set("items", productItems(entry, nextRefs.products()));
        r.put("itemsSource", "snapshot");
        if (approvedReceipt != null) {
          r.put("approvalId", approvedReceipt.path("id").asText()).put("instanceCode", approvedReceipt.path("instanceCode").asText())
              .put("receiptDate", approvedReceipt.path("receiptDate").asText()).put("receiptCategory", approvedReceipt.path("receiptCategory").asText());
          for (var allocation : approvedReceipt.path("allocations")) {
            if (!entry.path("id").equals(allocation.path("orderId"))) continue;
            var snapshots = r.putArray("items");
            for (var source : allocation.path("items")) {
              var item = snapshots.addObject();
              for (String field : List.of("product", "name", "sku", "model", "qty", "price"))
                if (source.has(field)) item.set(field, source.path(field).deepCopy());
            }
          }
        }
        receipts.add(r);
      }
    }
    if (body.has("receiptVerification")) {
      var verification = body.path("receiptVerification");
      var received = number(verification.path("amount"), false);
      require(received.signum() > 0 && received.stripTrailingZeros().scale() <= 2, "实际收款金额须大于零且最多两位小数");
      var allocated = receipts.stream().map(r -> r.path("amount").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
      require(!receipts.isEmpty() && received.compareTo(allocated) == 0, "实际收款金额与本次付款合计不一致");
      require(receipts.stream().allMatch(r -> r.path("customer").asText().equals(verification.path("customer").asText())
          && (!verification.has("customerId") || r.path("customerId").asText().equals(verification.path("customerId").asText()))
          && r.path("method").asText().equals(verification.path("method").asText())), "收款客户或收款方式不一致");
    }
    var balances = new HashMap<String, BigDecimal>();
    var all = new ArrayList<JsonNode>(historicalReceipts);
    all.addAll(receipts);
    var unresolvedNames = new HashSet<String>();
    for (var receipt : all) {
      String id = receipt.path("customerId").asText();
      if (id.isBlank()) { unresolvedNames.add(receipt.path("customer").asText()); continue; }
      require(nextCustomers.containsKey(id), "不能删除有收款记录的客户");
      BigDecimal amount = receiptAmount(receipt);
      BigDecimal delta = BigDecimal.ZERO;
      if (receipt.path("type").asText().equals("预收款订单")) delta = delta.add(amount);
      if (receipt.path("method").asText().equals("预收款核销")) delta = delta.subtract(amount);
      balances.merge(id, delta, BigDecimal::add);
    }
    for (var customer : next.path("customers")) {
      String id = customer.path("id").asText();
      var prior = oldCustomers.get(id);
      BigDecimal balance = LegacyCustomerData.opening(customer).add(balances.getOrDefault(id, BigDecimal.ZERO));
      if (prior != null && unresolvedNames.contains(prior.path("name").asText()))
        balance = money(prior.path("balance"), true);
      require(balance.signum() >= 0, "客户预收款余额不足");
      ((ObjectNode) customer).put("balance", balance.setScale(2, RoundingMode.UNNECESSARY));
    }
    assets.externalize(next);
    SettlementOrderNumbers.assignMissing(next);
    next.put("revision", old.path("revision").asLong(0) + 1);
    db.state(next);
    migratedReceipts.forEach(db::receipt);
    receipts.forEach(db::receipt);
    auditRecords("orders", "订单", old, next, actor);
    auditRecords("customers", "客户", old, next, actor);
    for (var receipt : receipts) {
      var detail = db.object().put("type", "RECEIPT_CREATED").put("source", "CRM")
          .put("orderId", receipt.path("order").asText()).put("role", actor.role());
      detail.set("after", receipt.deepCopy());
      db.audit(actor.name(), "新增收款流水", detail);
    }
    for (var receipt : migratedReceipts) {
      var detail = db.object().put("type", "RECEIPT_ASSOCIATION_UPDATED").put("source", "CRM")
          .put("orderId", receipt.path("order").asText()).put("role", actor.role());
      var before = receiptOriginals.get(receipt.path("id").asText());
      detail.set("before", before); detail.set("after", receipt.deepCopy());
      db.audit(actor.name(), "补全历史收款客户归属", detail);
    }
    db.audit(actor.name(), receipts.isEmpty() ? "保存业务数据" : "登记收款");
    return next;
  }

  private void auditRecords(String collection, String label, JsonNode old, JsonNode next, Actor actor) {
    var beforeIndex = index(old.path(collection));
    var afterIndex = index(next.path(collection));
    var ids = new TreeSet<String>(beforeIndex.keySet()); ids.addAll(afterIndex.keySet());
    for (String id : ids) {
      JsonNode before = beforeIndex.getOrDefault(id, NullNode.instance);
      JsonNode after = afterIndex.getOrDefault(id, NullNode.instance);
      if (before.equals(after)) continue;
      var detail = db.object().put("type", collection.equals("orders") ? "ORDER_CHANGE" : collection.equals("receipts") ? "RECEIPT_CHANGE" : "CUSTOMER_CHANGE")
          .put("source", "CRM").put("recordId", id).put("role", actor.role())
          .put("revisionBefore", old.path("revision").asLong()).put("revisionAfter", next.path("revision").asLong());
      if (collection.equals("orders")) detail.put("orderId", id);
      if (collection.equals("receipts")) detail.put("orderId", after.path("order").asText(before.path("order").asText()));
      detail.set("before", before.deepCopy()); detail.set("after", after.deepCopy());
      var fields = new TreeSet<String>(); before.fieldNames().forEachRemaining(fields::add); after.fieldNames().forEachRemaining(fields::add);
      var changes = detail.putArray("changes");
      for (String field : fields) if (!before.path(field).equals(after.path(field))) {
        var change = changes.addObject().put("field", field);
        change.set("before", before.has(field) ? before.get(field).deepCopy() : NullNode.instance);
        change.set("after", after.has(field) ? after.get(field).deepCopy() : NullNode.instance);
      }
      db.audit(actor.name(), (before.isNull() ? "新建" : after.isNull() ? "删除" : "修改") + label, detail);
    }
  }

  @Transactional(readOnly = true)
  public Map<String, Object> orderHistory(String id, Actor actor) {
    actor.require("business.read");
    var entries = db.rows("SELECT payload FROM audit WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.details.orderId'))=? ORDER BY seq", id)
        .stream().map(row -> db.parse(row.get("payload").toString())).toList();
    return Map.of("orderId", id, "exportedAt", CrmRepository.now(), "entries", db.expandAudits(entries));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> backup() {
    var snapshot = state();
    var refs = references(snapshot);
    return Map.of(
        "version",
        1,
        "state",
        snapshot,
        "receipts",
        db.documents("receipts", false, 0).stream().map(r -> normalizeReceipt(r, refs)).toList(),
        "assets",
        assets.backup(snapshot),
        "jstShipments",
        db.rows("SELECT payload FROM jst_shipments").stream().map(r -> db.parse(r.get("payload").toString())).toList(),
        "jstDocumentLinks",
        JushuitanDocumentBackup.exportLinks(db),
        "feishuApprovals",
        db.rows("SELECT payload FROM feishu_order_approvals ORDER BY order_id").stream().map(r -> db.parse(r.get("payload").toString())).toList(),
        "feishuReceiptApprovals",
        db.rows("SELECT payload FROM feishu_receipt_approvals ORDER BY id").stream().map(r -> db.parse(r.get("payload").toString())).toList(),
        "exported",
        CrmRepository.now());
  }

  private void collectShipmentProducts(JsonNode node, Set<String> ids) {
    if (node.isObject()) {
      if (node.has("product")) ids.add(node.path("product").asText());
      node.elements().forEachRemaining(child -> collectShipmentProducts(child, ids));
    } else if (node.isArray()) node.forEach(child -> collectShipmentProducts(child, ids));
  }

  private void protectShipmentRestore(JsonNode backup) {
    var receipts = index(backup.path("receipts"));
    for (var row : db.rows("SELECT payload FROM jst_shipments")) {
      var current = db.parse(row.get("payload").toString());
      JsonNode saved = null;
      for (var item : backup.path("jstShipments"))
        if (item.path("orderId").equals(current.path("orderId"))) saved = item;
      require(saved != null, "备份缺少现有聚水潭发货记录，不能回退外部订单状态");
      for (String key : List.of("soId", "oId", "request", "snapshot", "effectiveOrder", "change", "changes", "amendment", "amendments", "externalDifference", "externalChanges", "externalTimeline",
          "creditFulfillment", "accountingMode", "approvalUuid", "instanceCode", "attemptId", "upstreamAttempted", "confirmedRejected"))
        require(saved.path(key).equals(current.path(key)), "备份早于现有聚水潭变更，禁止回退；请使用最新备份");
      var protectedIds = new HashSet<String>();
      collectSettlements(current, protectedIds);
      for (var receipt : db.documents("receipts", false, 0))
        if (protectedIds.contains(receipt.path("id").asText()))
          require(receipt.equals(receipts.get(receipt.path("id").asText())), "备份缺少或修改了订单变更差额流水，禁止恢复");
      for (String id : protectedIds) require(receipts.containsKey(id), "备份缺少订单变更差额流水");
      var products = new HashSet<String>(); collectShipmentProducts(current, products);
      require(index(backup.path("state").path("products")).keySet().containsAll(products), "备份缺少发货及变更单引用商品");
    }
  }

  private void collectSettlements(JsonNode node, Set<String> ids) {
    if (node.isObject()) {
      if (node.path("settlement").isObject()) ids.add(node.path("settlement").path("id").asText());
      node.elements().forEachRemaining(child -> collectSettlements(child, ids));
    } else if (node.isArray()) node.forEach(child -> collectSettlements(child, ids));
  }

  private void protectLegacyRestore(JsonNode old, JsonNode next, JsonNode backupReceipts) {
    var currentRefs = references(old);
    var restoredCustomers = index(next.path("customers"));
    var restoredReceipts = index(backupReceipts);
    var historical = db.documents("receipts", false, 0).stream().map(r -> normalizeReceipt(r, currentRefs)).toList();
    var legacyIds = new HashSet<String>();
    for (var customer : old.path("customers")) {
      if (!customer.has("legacySource") && !customer.has("openingBalance")) continue;
      String id = customer.path("id").asText(); legacyIds.add(id);
      var saved = restoredCustomers.get(id);
      boolean hasHistory = historical.stream().anyMatch(r -> belongsTo(r, customer));
      require(saved != null || (LegacyCustomerData.opening(customer).signum() == 0 && !hasHistory),
          "备份缺少已有期初余额或收款历史的旧客户，请使用最新备份");
      if (saved == null) continue;
      for (String field : LegacyCustomerData.PROTECTED_FIELDS)
        require(same(customer.path(field), saved.path(field)), "备份缺少或修改了旧客户期初余额及来源，请使用最新备份");
    }
    for (var current : historical) {
      if (!legacyIds.contains(current.path("customerId").asText())) continue;
      var saved = restoredReceipts.get(current.path("id").asText());
      require(saved != null && sameRecord(current, normalizeReceipt(saved, currentRefs)),
          "备份缺少或修改了旧客户收款及核销流水，请使用最新备份");
    }
    var restoredOrders = index(next.path("orders"));
    restoredOrders.values().forEach(OrderWarehouse::validateDraft);
    restoredOrders.values().forEach(JushuitanCreditPushPolicy::validateDraft);
    for (var current : old.path("orders")) {
      String id = current.path("customerId").asText();
      if (id.isBlank()) {
        var customer = currentRefs.names().get(current.path("customer").asText());
        if (customer != null) id = customer.path("id").asText();
      }
      if (!legacyIds.contains(id) || money(current.path("paid"), true).signum() == 0) continue;
      var saved = restoredOrders.getOrDefault(current.path("id").asText(), NullNode.instance);
      require(!saved.isNull() && !coreChanged(current, saved) && same(current.path("paid"), saved.path("paid"))
          && same(current.path("status"), saved.path("status")),
          "备份早于旧客户订单的最新收款或核销，请使用最新备份");
    }
  }

  @Transactional
  public void restore(JsonNode body, Actor actor) {
    require(body.path("version").asInt() == 1 && body.path("receipts").isArray(), "备份格式无效");
    validState(body.path("state"));
    // Stop old catalog checkpoints before taking the state lock; restored caches need a fresh full import.
    db.jdbc().update("DELETE FROM jst_catalog_jobs");
    var old = db.state(true);
    protectShipmentRestore(body);
    ObjectNode next = body.path("state").deepCopy();
    SettlementOrderNumbers.protect(old, next, true);
    protectLegacyRestore(old, next, body.path("receipts"));
    var restoredOrders = index(next.path("orders"));
    protectCanceledOrders(index(old.path("orders")), restoredOrders, true);
    protectDeletedOrders(index(old.path("orders")), restoredOrders, true);
    // Compare approval locks against the same historical customer view exported by backup().
    // The original document remains untouched for financial checks and restoration audits.
    JsonNode approvalBaseline = old.deepCopy();
    if (!approvalBaseline.isNull()) normalizeOrders(approvalBaseline, approvalBaseline);
    var priorApprovalOrders = index(approvalBaseline.path("orders"));
    protectFeishuOrders(priorApprovalOrders, restoredOrders, "", false);
    protectApprovalRestore(body, restoredOrders);
    protectReceiptApprovalOrders(priorApprovalOrders, restoredOrders, "");
    protectReceiptRestore(body, old);
    protectSubmittedOrders(restoredOrders);
    for (var current : old.path("orders")) {
      if (!JushuitanDocumentService.isSettlement(current)) continue;
      JsonNode saved = restoredOrders.getOrDefault(current.path("id").asText(), NullNode.instance);
      require(JushuitanDocumentService.isSettlement(saved)
          && !coreChanged(current, saved)
          && same(current.path("status"), saved.path("status"))
          && same(current.path("paid"), saved.path("paid"))
          && sameRecord(current.path("settlement"), saved.path("settlement")),
          "备份缺少现有聚水潭结算单或其最新收款记录，请使用最新备份");
    }
    var restoredReceipts = index(body.path("receipts"));
    var currentRefs = references(old);
    for (var receipt : db.documents("receipts", false, 0)) {
      var currentOrder = currentRefs.orders().get(receipt.path("order").asText());
      if (JushuitanDocumentService.isSettlement(currentOrder)) {
        var saved = restoredReceipts.get(receipt.path("id").asText());
        require(saved != null && sameRecord(normalizeReceipt(receipt, currentRefs),
            normalizeReceipt(saved, currentRefs)), "备份缺少现有聚水潭结算收款流水，请使用最新备份");
      }
    }
    normalizeOrders(next, next);
    List<JsonNode> shipmentsToImport = new ArrayList<>();
    if (body.has("jstShipments")) {
      require(body.path("jstShipments").isArray(), "发货备份格式无效");
      var orderIndex = index(next.path("orders"));
      var shipmentIds = new HashSet<String>();
      for (var shipment : body.path("jstShipments")) {
        String id = shipment.path("orderId").asText();
        var order = orderIndex.get(id);
        require(order != null && shipmentIds.add(id) && id.length() <= 191
            && shipment.path("soId").asText().matches("CRM-[a-f0-9]{32}")
            && shipment.path("soId").equals(shipment.path("request").path("so_id"))
            && List.of("sandbox", "production").contains(shipment.path("environment").asText())
            && !coreChanged(order, shipment.path("snapshot"))
            && same(order.path("status"), shipment.path("snapshot").path("status")), "发货备份与订单不匹配");
        ObjectNode record = shipment.deepCopy();
        record.put("busy", false);
        if (record.path("status").asText().equals("SUBMITTING")) record.put("status", "UNKNOWN");
        shipmentsToImport.add(record);
      }
    }
    var restoredCustomers = index(next.path("customers"));
    for (var order : next.path("orders")) {
      BigDecimal total = money(order.path("total"), false), paid = money(order.path("paid"), true);
      require(paid.compareTo(total) <= 0, "备份中订单已付金额超过订单总额");
      if (JushuitanDocumentService.isSettlement(order)) {
        require(total.signum() > 0 && "销售订单".equals(order.path("type").asText())
            && order.path("items").isArray() && order.path("items").isEmpty()
            && restoredCustomers.containsKey(order.path("customerId").asText())
            && List.of("审批中", "已批准", "草稿").contains(order.path("status").asText())
            && (paid.signum() == 0 || "已批准".equals(order.path("status").asText())),
            "备份中的聚水潭补录总单信息无效");
      }
    }
    var receiptIds = new HashSet<String>();
    var settlementPayments = new HashMap<String, BigDecimal>();
    for (var receipt : body.path("receipts")) {
      require(receipt.isObject() && !receipt.path("id").asText().isBlank()
          && receiptIds.add(receipt.path("id").asText()), "备份流水ID无效或重复");
      var amount = receiptAmount(receipt);
      require(amount.signum() != 0, "备份流水金额无效");
      String orderId = receipt.path("order").asText();
      var order = restoredOrders.get(orderId);
      if (JushuitanDocumentService.isSettlement(order)) {
        require(amount.signum() > 0 && order.path("customerId").equals(receipt.path("customerId"))
            && "销售订单".equals(receipt.path("type").asText())
            && !receipt.path("method").asText().isBlank()
            && !"预收款核销".equals(receipt.path("method").asText()), "备份中的结算收款流水信息无效");
        JushuitanSettlementService.receivedOn(receipt.path("receivedOn").asText(), order);
        settlementPayments.merge(orderId, amount, BigDecimal::add);
      }
    }
    for (var order : next.path("orders"))
      if (JushuitanDocumentService.isSettlement(order))
        require(money(order.path("paid"), true).compareTo(settlementPayments.getOrDefault(
            order.path("id").asText(), BigDecimal.ZERO)) == 0, "备份中的结算总单和收款流水金额不一致");
    // Opening credit travels with the JSON backup; it never becomes a synthetic receipt.
    var openingDeltas = new HashMap<String, BigDecimal>();
    var restoredRefs = references(next);
    for (var receipt : body.path("receipts")) {
      var normalized = normalizeReceipt(receipt, restoredRefs);
      String id = normalized.path("customerId").asText();
      BigDecimal delta = BigDecimal.ZERO;
      if (normalized.path("type").asText().equals("预收款订单")) delta = delta.add(receiptAmount(normalized));
      if (normalized.path("method").asText().equals("预收款核销")) delta = delta.subtract(receiptAmount(normalized));
      openingDeltas.merge(id, delta, BigDecimal::add);
    }
    for (var customer : next.path("customers")) {
      if (!customer.has("openingBalance")) continue;
      var expected = LegacyCustomerData.opening(customer).add(openingDeltas.getOrDefault(customer.path("id").asText(), BigDecimal.ZERO));
      require(expected.signum() >= 0 && money(customer.path("balance"), false).compareTo(expected) == 0,
          "备份中的客户余额与期初余额及预收款流水不一致");
    }
    JushuitanDocumentBackup.restoreLinks(db, body.path("jstDocumentLinks"), restoredOrders, shipmentsToImport, old);
    assets.restore(body.path("assets"));
    assets.externalize(next);
    SettlementOrderNumbers.assignMissing(next);
    next.put("revision", old.path("revision").asLong() + 1);
    db.state(next);
    var receiptsBefore = db.object();
    var beforeRows = receiptsBefore.putArray("receipts");
    db.documents("receipts", false, 0).forEach(beforeRows::add);
    var receiptsAfter = db.object();
    var afterRows = receiptsAfter.putArray("receipts");
    db.jdbc().update("DELETE FROM receipts");
    var refs = references(next);
    for (var r : body.path("receipts")) {
      require(!r.path("id").asText().isBlank(), "流水ID无效");
      var normalized = normalizeReceipt(r, refs);
      db.receipt(normalized);
      afterRows.add(normalized);
    }
    for (var shipment : shipmentsToImport)
      db.jdbc().update("INSERT IGNORE INTO jst_shipments(order_id,payload) VALUES(?,?)", shipment.path("orderId").asText(), db.encode(shipment));
    auditRecords("orders", "订单（恢复备份）", old, next, actor);
    auditRecords("customers", "客户（恢复备份）", old, next, actor);
    auditRecords("receipts", "收款流水（恢复备份）", receiptsBefore, receiptsAfter, actor);
    db.audit(actor.name(), "恢复数据备份");
  }

  @Transactional
  public JsonNode deleteOrder(String id, Actor actor) {
    actor.require("business.read"); actor.require("business.write");
    require(!id.isBlank() && id.length() <= 191, "订单编号无效");
    var state = db.state(true);
    normalizeOrders(state, state);
    var order = index(state.path("orders")).get(id);
    if (order == null) throw new ApiException(404, "订单不存在");
    if (deletedOrder(order)) return state;
    require(Set.of("销售订单", "海外提货单").contains(order.path("type").asText())
        && !JushuitanDocumentService.isSettlement(order)
        && DELETABLE_ORDER_STATUSES.contains(order.path("status").asText()), "仅未批准的销售订单或海外提货单可删除");
    require(canStopFinancially(db, order), "已有收款、财务流水或聚水潭关联的订单不能删除");
    var approvals = db.rows("SELECT status,instance_code FROM feishu_order_approvals WHERE order_id=? FOR UPDATE", id);
    if (!approvals.isEmpty()) {
      var approval = approvals.getFirst();
      String status = Objects.toString(approval.get("status"), "");
      boolean linked = !Objects.toString(approval.get("instance_code"), "").isBlank();
      require(STOPPED_APPROVAL_STATUSES.contains(status) || (!linked && "FAILED".equals(status)),
          "请先取消订单并确认飞书审批已结束");
      require(!linked || canceledOrder(order), "已有飞书审批记录，请先取消订单");
    }
    String deletedAt = CrmRepository.now();
    ((ObjectNode) order).put("status", CANCELED_ORDER).put("deletedAt", deletedAt);
    ((ObjectNode) state).put("revision", state.path("revision").asLong() + 1);
    db.state(state);
    db.audit(actor.name(), "删除" + order.path("type").asText(), db.object().put("type", "ORDER_DELETED").put("source", "CRM")
        .put("orderId", id).put("deletedAt", deletedAt));
    return state;
  }

  static boolean hasJushuitanOrderLink(CrmRepository db, String id) {
    return !db.rows("""
        SELECT order_id FROM jst_shipments WHERE order_id=?
        UNION ALL SELECT order_id FROM jst_document_sales WHERE order_id=?
        UNION ALL SELECT order_id FROM jst_document_purchases WHERE order_id=? LIMIT 1
        """, id, id, id).isEmpty();
  }

  static boolean canStopFinancially(CrmRepository db, JsonNode order) {
    try {
      if (new BigDecimal(order.path("paid").asText("0")).signum() != 0) return false;
    } catch (NumberFormatException failure) { return false; }
    String id = order.path("id").asText();
    return !hasActiveReceiptApproval(db, id) && !hasJushuitanOrderLink(db, id) && db.rows(
        "SELECT id FROM receipts WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.order'))=? LIMIT 1", id).isEmpty();
  }

  static boolean hasActiveReceiptApproval(CrmRepository db, String orderId) {
    for (var row : db.rows(ACTIVE_RECEIPT_APPROVALS)) {
      var batch = db.parse(row.get("payload").toString());
      for (var allocation : batch.path("allocations"))
        if (orderId.equals(allocation.path("orderId").asText())) return true;
    }
    return false;
  }

  private void protectReceiptApprovalOrders(Map<String, JsonNode> before, Map<String, JsonNode> after, String ownBatchId) {
    for (var row : db.rows(ACTIVE_RECEIPT_APPROVALS)) {
      var batch = db.parse(row.get("payload").toString());
      if (!ownBatchId.isBlank() && ownBatchId.equals(batch.path("id").asText())) continue;
      for (var allocation : batch.path("allocations")) {
        String id = allocation.path("orderId").asText();
        var previous = before.get(id); var next = after.get(id);
        require(previous != null && next != null, "有收款审批记录的订单不能移除");
        for (String field : RECEIPT_APPROVAL_FIELDS)
          require(same(previous.path(field), next.path(field)), "有待处理收款审批的订单不能修改提交内容或已收金额");
      }
    }
  }

  private void protectReceiptRestore(JsonNode backup, JsonNode current) {
    var saved = new HashMap<String, JsonNode>();
    for (var record : backup.path("feishuReceiptApprovals")) {
      String id = record.path("id").asText();
      require(!id.isBlank() && saved.putIfAbsent(id, record) == null, "备份中的收款审批无效或重复");
    }
    var currentIds = new HashSet<String>();
    for (var row : db.rows("SELECT payload FROM feishu_receipt_approvals ORDER BY id")) {
      var batch = db.parse(row.get("payload").toString());
      String id = batch.path("id").asText(); currentIds.add(id);
      require(batch.equals(saved.get(id)), "备份缺少收款审批或早于最新状态，请使用最新备份");
    }
    require(currentIds.equals(saved.keySet()), "不能从备份导入未经核对的外部收款审批");
    var incoming = index(backup.path("receipts"));
    var before = index(current.path("orders")); var after = index(backup.path("state").path("orders"));
    if (!feishuReceiptApprovalCode.isBlank()) {
      var refs = references(current);
      var history = db.documents("receipts", false, 0);
      require(history.size() == incoming.size(), "收款审批启用后，备份不能新增或移除收款流水");
      for (var receipt : history)
        require(normalizeReceipt(receipt, refs).equals(incoming.get(receipt.path("id").asText())),
            "收款审批启用后，备份不能修改收款流水");
      for (var entry : after.entrySet()) {
        var previous = before.get(entry.getKey());
        require(previous == null ? money(entry.getValue().path("paid"), true).signum() == 0
            : same(previous.path("paid"), entry.getValue().path("paid")),
            "收款审批启用后，备份不能改变订单已收金额");
      }
    }
    for (var receipt : db.documents("receipts", false, 0)) {
      if (receipt.path("approvalId").asText().isBlank()) continue;
      require(normalizeReceipt(receipt, references(current)).equals(incoming.get(receipt.path("id").asText())), "备份缺少或修改了审批通过的收款流水");
      String id = receipt.path("order").asText();
      require(before.containsKey(id) && after.containsKey(id) && same(before.get(id).path("paid"), after.get(id).path("paid")),
          "备份不能回退已审批收款的订单已收金额");
    }
  }

  private void protectCanceledOrders(Map<String, JsonNode> before, Map<String, JsonNode> after, boolean restoring) {
    for (var entry : before.entrySet()) {
      if (!canceledOrder(entry.getValue())) continue;
      var next = after.get(entry.getKey());
      require(canceledOrder(next) && sameRecord(entry.getValue(), next), "已取消订单不能恢复、删除记录或修改");
    }
    for (var entry : after.entrySet()) {
      var order = entry.getValue();
      if (!canceledOrder(order)) continue;
      var prior = before.get(entry.getKey());
      require(canceledOrder(prior) || (restoring && prior == null), "请通过订单的取消操作取消订单");
      require(APPROVAL_TYPES.contains(order.path("type").asText()) && !JushuitanDocumentService.isSettlement(order)
          && money(order.path("paid"), true).signum() == 0, "已取消订单须为未收款的普通业务订单");
    }
  }

  private void protectDeletedOrders(Map<String, JsonNode> before, Map<String, JsonNode> after, boolean restoring) {
    for (var entry : before.entrySet()) {
      if (!deletedOrder(entry.getValue())) continue;
      var next = after.get(entry.getKey());
      require(next != null && entry.getValue().path("deletedAt").equals(next.path("deletedAt")), "已删除订单不能恢复或移除记录");
    }
    for (var entry : after.entrySet()) {
      var order = entry.getValue();
      if (!order.has("deletedAt")) continue;
      var prior = before.get(entry.getKey());
      require(deletedOrder(prior) || (restoring && prior == null), "请通过订单的删除操作删除订单");
      require(order.path("deletedAt").isTextual() && !order.path("deletedAt").asText().isBlank()
          && canceledOrder(order) && Set.of("销售订单", "海外提货单").contains(order.path("type").asText()), "已删除订单记录无效");
    }
  }

  private static boolean canceledOrder(JsonNode order) { return order != null && CANCELED_ORDER.equals(order.path("status").asText()); }
  private static boolean deletedOrder(JsonNode order) { return order != null && order.hasNonNull("deletedAt"); }

  private boolean requiresFeishu(JsonNode order) {
    return order != null && APPROVAL_TYPES.contains(order.path("type").asText());
  }

  private boolean onlyLegacyCustomerAssociationChanged(JsonNode next, JsonNode prior) {
    if (prior == null || !prior.path("customerId").asText().isBlank() || next.path("customerId").asText().isBlank()) return false;
    for (String field : List.of("status", "total", "type", "items", "paid", "owner"))
      if (!same(next.path(field), prior.path(field))) return false;
    return OrderWarehouse.same(next, prior) && JushuitanCreditPushPolicy.samePlan(next, prior);
  }

  private void protectFeishuOrders(Map<String, JsonNode> before, Map<String, JsonNode> after,
      String submittingOrderId, boolean allowLegacyCustomerAssociation) {
    for (var entry : after.entrySet()) {
      var next = entry.getValue();
      if (!requiresFeishu(next)) continue;
      var prior = before.get(entry.getKey());
      if ("已批准".equals(next.path("status").asText()))
        require(prior != null && "已批准".equals(prior.path("status").asText())
            && ((!coreChanged(next, prior) && same(next.path("owner"), prior.path("owner")))
                || (allowLegacyCustomerAssociation && onlyLegacyCustomerAssociationChanged(next, prior))),
            "订单须通过飞书审批，不能在 CRM 手工批准或修改已批准内容");
      if ("审批中".equals(next.path("status").asText()) && !entry.getKey().equals(submittingOrderId))
        require(prior != null && "审批中".equals(prior.path("status").asText()) && !coreChanged(next, prior)
            && same(next.path("owner"), prior.path("owner")), "请使用提交飞书审批，不能仅修改 CRM 审批状态");
    }
    for (var row : db.rows("SELECT order_id,status FROM feishu_order_approvals")) {
      String id = row.get("order_id").toString();
      var previous = before.get(id);
      var next = after.get(id);
      require(previous != null && next != null, "有飞书审批历史的订单不能移除");
      if (!Set.of("QUEUED", "SUBMITTING", "UNKNOWN", "PENDING", "APPROVED").contains(row.get("status").toString())) continue;
      for (String field : APPROVAL_FIELDS)
        require(same(previous.path(field), next.path(field)), "有飞书审批记录的订单不能修改审批状态及提交内容");
    }
  }

  private void protectApprovalRestore(JsonNode backup, Map<String, JsonNode> orders) {
    var saved = new HashMap<String, JsonNode>();
    for (var record : backup.path("feishuApprovals")) {
      String id = record.path("orderId").asText();
      require(!id.isBlank() && saved.putIfAbsent(id, record) == null, "备份中的飞书审批记录无效或重复");
    }
    var currentIds = new HashSet<String>();
    for (var row : db.rows("SELECT payload FROM feishu_order_approvals ORDER BY order_id")) {
      var current = db.parse(row.get("payload").toString());
      String id = current.path("orderId").asText(); currentIds.add(id);
      require(orders.containsKey(id) && current.equals(saved.get(id)), "备份缺少现有飞书审批或早于最新审批状态，请使用最新备份");
    }
    require(currentIds.equals(saved.keySet()), "不能从备份导入未经核对的外部飞书审批记录");
  }

  private void protectSubmittedOrders(Map<String, JsonNode> orders) {
    for (var row : db.rows("SELECT payload FROM jst_shipments")) {
      var shipment = db.parse(row.get("payload").toString());
      var order = orders.get(shipment.path("orderId").asText());
      var snapshot = shipment.path("snapshot");
      require(order != null && !coreChanged(order, snapshot)
          && same(order.path("status"), snapshot.path("status")),
          "订单已提交聚水潭，不能修改客户、商品、金额和审批状态；请通过发货详情变更收件信息或备注；商品金额变更尚未开放");
    }
  }
}
