package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessService {
  private final CrmRepository db;
  private final AssetService assets;

  public BusinessService(CrmRepository db, AssetService assets) {
    this.db = db;
    this.assets = assets;
  }

  public JsonNode state() {
    var state = db.state(false);
    if (!state.isNull()) normalizeOrders(state, state);
    return state;
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

  private record References(Map<String, JsonNode> orders, Map<String, JsonNode> customers, Map<String, JsonNode> names) {}

  private References references(JsonNode state) {
    return new References(index(state.path("orders")), index(state.path("customers")), customersByName(state));
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
    return false;
  }

  private void validateTotal(JsonNode order, JsonNode products) {
    if (order.path("type").asText().equals("预收款订单")) return;
    require(order.path("items").isArray() && !order.path("items").isEmpty(), "销售订单须填写产品明细");
    var byId = index(products);
    BigDecimal total = BigDecimal.ZERO;
    for (var item : order.path("items")) {
      require(byId.containsKey(item.path("product").asText()), "订单产品不存在");
      BigDecimal quantity = number(item.path("qty"), false);
      require(quantity.signum() > 0 && quantity.compareTo(new BigDecimal("1000000000")) <= 0
          && quantity.stripTrailingZeros().scale() <= 6, "产品数量须大于零，最多六位小数且不超过十亿");
      total = total.add(quantity.multiply(money(item.path("price"), false)));
    }
    require(total.setScale(2, RoundingMode.HALF_UP).compareTo(money(order.path("total"), false)) == 0,
        "订单总额与产品明细合计不一致");
  }

  @Transactional(readOnly = true)
  public List<JsonNode> receipts() {
    var state = state();
    var refs = references(state);
    return db.documents("receipts", true, 0).stream().map(r -> normalizeReceipt(r, refs)).toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> receiptsPage(int page, int pageSize, String query, String method) {
    require(page >= 1 && pageSize >= 1 && pageSize <= 100 && query.length() <= 200 && method.length() <= 50,
        "分页或筛选参数无效");
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
          return receipt;
        }).toList();
    var methods = db.rows("SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.method')) AS method FROM receipts ORDER BY method")
        .stream().map(row -> row.get("method")).filter(Objects::nonNull).toList();
    return Map.of("rows", rows, "total", total, "page", currentPage, "amount", summary.get("amount"),
        "customers", summary.get("customers"), "methods", methods);
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

  private JsonNode save(JsonNode body, Actor actor, boolean migration) {
    actor.require("business.read");
    actor.require("business.write");
    var old = db.state(true);
    if (!old.isNull() && !same(body.path("revision"), old.path("revision")))
      throw new ApiException(409, "数据已由其他操作更新，请刷新后重试");
    var input = body.path("data");
    validState(input);
    ObjectNode next = input.deepCopy();
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
      require(List.of("草稿", "审批中", "已批准").contains(entry.path("status").asText()), "订单状态无效");
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
        r.put("id", UUID.randomUUID().toString());
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
    return Map.of(
        "version",
        1,
        "state",
        state(),
        "receipts",
        receipts().reversed(),
        "assets",
        assets.backup(state()),
        "jstShipments",
        db.rows("SELECT payload FROM jst_shipments").stream().map(r -> db.parse(r.get("payload").toString())).toList(),
        "jstDocumentLinks",
        JushuitanDocumentBackup.exportLinks(db),
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
      for (String key : List.of("soId", "oId", "request", "effectiveOrder", "change", "changes", "amendment", "amendments", "externalDifference", "externalChanges", "externalTimeline"))
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
    protectLegacyRestore(old, next, body.path("receipts"));
    var restoredOrders = index(next.path("orders"));
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
