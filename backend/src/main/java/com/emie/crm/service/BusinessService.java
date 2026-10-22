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
    actor.require("business.read");
    actor.require("business.write");
    var old = db.state(true);
    if (!old.isNull() && !same(body.path("revision"), old.path("revision")))
      throw new ApiException(409, "数据已由其他操作更新，请刷新后重试");
    var input = body.path("data");
    validState(input);
    ObjectNode next = input.deepCopy();
    customersByName(next);
    var oldCustomers = index(old.path("customers"));
    var nextCustomers = index(next.path("customers"));
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
    var oldRefs = references(old);
    var nextRefs = references(next);
    var historicalReceipts = new ArrayList<JsonNode>();
    var migratedReceipts = new ArrayList<JsonNode>();
    for (var receipt : db.documents("receipts", false, 0)) {
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
      if (lineItemsChanged) validateTotal(entry, next.path("products"));
      if (prior.path("status").asText().equals("已批准")
          && (changed || !same(entry.path("status"), prior.path("status")))) actor.require("order.approve");
      if (entry.path("status").asText().equals("已批准")
          && !prior.path("status").asText().equals("已批准")) actor.require("order.approve");
      BigDecimal total = money(entry.path("total"), false),
          paid = money(entry.path("paid"), true),
          before = number(prior.path("paid"), true),
          delta = paid.subtract(before);
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
      BigDecimal balance = balances.getOrDefault(id, BigDecimal.ZERO);
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
    db.audit(actor.name(), receipts.isEmpty() ? "保存业务数据" : "登记收款");
    return next;
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
      for (String key : List.of("soId", "oId", "request", "effectiveOrder", "change", "changes", "amendment", "amendments"))
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

  @Transactional
  public void restore(JsonNode body, Actor actor) {
    require(body.path("version").asInt() == 1 && body.path("receipts").isArray(), "备份格式无效");
    validState(body.path("state"));
    var old = db.state(true);
    protectShipmentRestore(body);
    ObjectNode next = body.path("state").deepCopy();
    protectSubmittedOrders(index(next.path("orders")));
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
    for (var order : next.path("orders")) {
      BigDecimal total = money(order.path("total"), false), paid = money(order.path("paid"), true);
      require(paid.compareTo(total) <= 0, "备份中订单已付金额超过订单总额");
    }
    var receiptIds = new HashSet<String>();
    for (var receipt : body.path("receipts")) {
      require(receipt.isObject() && !receipt.path("id").asText().isBlank()
          && receiptIds.add(receipt.path("id").asText()), "备份流水ID无效或重复");
      require(receiptAmount(receipt).signum() != 0, "备份流水金额无效");
    }
    assets.restore(body.path("assets"));
    assets.externalize(next);
    next.put("revision", old.path("revision").asLong() + 1);
    db.state(next);
    db.jdbc().update("DELETE FROM receipts");
    var refs = references(next);
    for (var r : body.path("receipts")) {
      require(!r.path("id").asText().isBlank(), "流水ID无效");
      db.receipt(normalizeReceipt(r, refs));
    }
    for (var shipment : shipmentsToImport)
      db.jdbc().update("INSERT IGNORE INTO jst_shipments(order_id,payload) VALUES(?,?)", shipment.path("orderId").asText(), db.encode(shipment));
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
