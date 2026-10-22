package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;
import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JushuitanService {
  private final CrmRepository db;
  private final JushuitanClient client;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final boolean sandbox;

  public JushuitanService(CrmRepository db, JushuitanClient client, ObjectMapper mapper,
      org.springframework.transaction.PlatformTransactionManager manager,
      @Value("${CRM_JST_ENV:sandbox}") String env) {
    this.db = db; this.client = client; this.mapper = mapper;
    this.tx = new TransactionTemplate(manager);
    require(Set.of("sandbox", "production").contains(env), "聚水潭环境配置无效");
    sandbox = env.equals("sandbox");
  }

  public Object config() { return Map.of("configured", client.configured(), "environment", sandbox ? "sandbox" : "production", "changesEnabled", client.changesEnabled()); }

  public List<JsonNode> list() {
    return db.rows("SELECT payload FROM jst_shipments ORDER BY updated_at DESC").stream()
        .map(r -> publicRecord(db.parse(r.get("payload").toString()))).toList();
  }

  private JsonNode publicRecord(JsonNode node) {
    ObjectNode result = node.deepCopy();
    result.remove(List.of("snapshot", "busy", "startedAt"));
    return result;
  }

  private ObjectNode load(String id, boolean lock) {
    var rows = db.rows("SELECT payload FROM jst_shipments WHERE order_id=?" + (lock ? " FOR UPDATE" : ""), id);
    return rows.isEmpty() ? null : (ObjectNode) db.parse(rows.getFirst().get("payload").toString());
  }

  private void store(ObjectNode record) {
    var previous = load(record.path("orderId").asText(), false);
    if (previous == null || !editState(previous).equals(editState(record)))
      record.put("editVersion", UUID.randomUUID().toString());
    else record.put("editVersion", previous.path("editVersion").asText(previous.path("updatedAt").asText()));
    record.put("updatedAt", CrmRepository.now());
    db.jdbc().update("UPDATE jst_shipments SET payload=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=?", db.encode(record), record.path("orderId").asText());
  }

  private ObjectNode editState(JsonNode record) {
    var value = mapper.createObjectNode();
    for (String key : List.of("soId", "oId", "status", "request", "effectiveOrder", "change", "amendment"))
      value.set(key, record.path(key));
    value.put("oId", record.path("oId").asLong());
    return value;
  }

  private void event(ObjectNode record, String message) {
    ArrayNode log = record.withArray("events");
    log.addObject().put("time", CrmRepository.now()).put("message", message);
    while (log.size() > 50) log.remove(0);
  }

  private JsonNode data(JsonNode response) {
    require(response.path("code").asInt(-1) == 0, "聚水潭接口未成功（代码 " + response.path("code").asText() + "），请检查接口权限及数据");
    return response.path("data");
  }

  public Object shops() {
    ArrayNode shops = mapper.createArrayNode();
    for (int page = 1; page <= 100; page++) {
      JsonNode result = data(client.query("/open/shops/query", Map.of("page_index", page, "page_size", 100)));
      for (var shop : result.path("datas"))
        if (Set.of("商家自有商城", "线下店铺", "B2B").contains(shop.path("shop_site").asText()))
          shops.addObject().put("id", shop.path("shop_id").asLong()).put("name", shop.path("shop_name").asText());
      if (!result.path("has_next").asBoolean()) return shops;
    }
    throw new ApiException("店铺数量过多，请联系管理员配置");
  }

  public JsonNode inventory(String sku) {
    require(!sku.isBlank() && sku.length() <= 40 && !sku.contains(","), "SKU编码无效");
    return data(client.query("/open/inventory/query", Map.of("sku_ids", sku, "page_index", 1, "page_size", 100))).path("inventorys");
  }

  private void validateSkus(ObjectNode request) {
    // A stock row alone is insufficient: deleted products may still have historical stock.
    for (var item : request.path("items")) {
      String sku = item.path("sku_id").asText();
      var info = data(client.query("/open/sku/query", Map.of("sku_ids", sku, "page_index", 1, "page_size", 100)));
      JsonNode match = null;
      for (var product : info.path("datas"))
        if (product.path("sku_id").asText().equals(sku) && product.path("enabled").asInt() == 1) match = product;
      require(match != null && !match.path("i_id").asText().isBlank(), "SKU " + sku + " 不存在、未启用或缺少款式编码");
      ((ObjectNode) item).put("i_id", match.path("i_id").asText()).put("shop_i_id", match.path("i_id").asText());
      boolean found = false;
      for (var stock : inventory(sku)) if (stock.path("sku_id").asText().equals(sku)) found = true;
      require(found, "SKU " + sku + " 尚无库存记录，请先检查聚水潭库存");
    }
    boolean validShop = false;
    for (var shop : data(client.query("/open/shops/query", Map.of("shop_ids", List.of(request.path("shop_id").asLong()), "page_index", 1, "page_size", 10))).path("datas"))
      if (shop.path("shop_id").asLong() == request.path("shop_id").asLong()
          && Set.of("商家自有商城", "线下店铺", "B2B").contains(shop.path("shop_site").asText())) validShop = true;
    require(validShop, "店铺不存在或不支持订单上传");
  }

  public synchronized JsonNode submit(String orderId, JsonNode input, Actor actor) {
    actor.require("business.write"); actor.require("order.approve");
    require(client.configured(), "尚未配置聚水潭云端服务");
    ObjectNode record = tx.execute(s -> {
      var state = db.state(true); // Serializes with CRM edits and other submissions.
      JsonNode order = null;
      for (var candidate : state.path("orders")) if (candidate.path("id").asText().equals(orderId)) order = candidate;
      require(order != null, "订单不存在");
      var prior = load(orderId, true);
      if (prior != null) {
        require(prior.path("environment").asText().equals(sandbox ? "sandbox" : "production"), "该订单已绑定另一聚水潭环境");
        require(prior.path("status").asText().equals("FAILED"), "该订单已提交，请先同步状态核对，不要重复推送");
        require(!prior.path("busy").asBoolean(), "订单正在处理，请稍后");
        if (!prior.path("upstreamAttempted").asBoolean()) {
          prior.set("request", JushuitanOrderMapper.build(mapper, order, state.path("products"), input, prior.path("soId").asText(), sandbox));
        }
        prior.put("status", "SUBMITTING").put("busy", true).put("startedAt", Instant.now().toEpochMilli());
        event(prior, "重新提交原始订单，沿用原聚水潭订单号"); store(prior); return prior;
      }
      String soId = "CRM-" + UUID.randomUUID().toString().replace("-", "");
      var request = JushuitanOrderMapper.build(mapper, order, state.path("products"), input, soId, sandbox);
      var next = db.object().put("orderId", orderId).put("soId", soId).put("environment", sandbox ? "sandbox" : "production")
          .put("status", "SUBMITTING").put("busy", true).put("startedAt", Instant.now().toEpochMilli())
          .put("createdAt", CrmRepository.now()).put("updatedAt", CrmRepository.now()).put("operator", actor.name());
      next.set("request", request); next.set("snapshot", order.deepCopy());
      event(next, "已登记发货请求，正在校验店铺及商品");
      db.jdbc().update("INSERT INTO jst_shipments(order_id,payload) VALUES(?,?)", orderId, db.encode(next));
      db.audit(actor.name(), "提交聚水潭发货：" + orderId);
      return next;
    });
    boolean uploading = false;
    try {
      var request = (ObjectNode) record.path("request");
      validateSkus(request);
      tx.executeWithoutResult(s -> { record.set("request", request); store(record); });
      uploading = true;
      record.put("upstreamAttempted", true);
      tx.executeWithoutResult(s -> store(record));
      var result = client.upload(mapper.createArrayNode().add(request));
      JsonNode match = null;
      for (var row : result.path("data").path("datas")) if (row.path("so_id").asText().equals(record.path("soId").asText())) match = row;
      if (match == null) for (var row : result.path("datas")) if (row.path("so_id").asText().equals(record.path("soId").asText())) match = row;
      if (match != null && match.path("issuccess").asBoolean() && match.path("o_id").asLong() > 0) {
        record.put("oId", match.path("o_id").asLong()).put("status", "ACCEPTED").put("message", "聚水潭已接单，待同步审核及发货状态");
      } else if (match != null && !match.path("issuccess").asBoolean() && match.path("o_id").asLong() == 0) {
        record.put("status", "FAILED").put("message", "聚水潭拒绝订单（" + safe(match.path("msg").asText()) + "）");
      } else {
        record.put("status", "UNKNOWN").put("message", "推送结果待核对，请同步状态后再处理");
      }
    } catch (Exception e) {
      record.put("status", uploading ? "UNKNOWN" : "FAILED").put("message", e instanceof ApiException ? e.getMessage() : "接口处理失败，请同步核对订单");
    }
    record.put("busy", false); event(record, record.path("message").asText());
    tx.executeWithoutResult(s -> store(record));
    return publicRecord(record);
  }

  private static final Map<String, String> RECIPIENT_FIELDS = Map.of(
      "name", "receiver_name", "phone", "receiver_mobile", "province", "receiver_state",
      "city", "receiver_city", "district", "receiver_district", "address", "receiver_address");

  /** Persist intent before any external write; reconciliation alone confirms success. */
  public synchronized JsonNode change(String orderId, JsonNode input, Actor actor) {
    actor.require("business.write"); actor.require("order.approve");
    require(client.configured(), "尚未配置聚水潭云端服务");
    require(client.changesEnabled(), "订单变更服务尚未启用，请先开通接口权限并更新云端网关");
    String kind = input.path("kind").asText();
    require(Set.of("ADDRESS", "REMARK", "CANCEL").contains(kind), "请选择收件信息、发货备注或取消订单；商品金额变更尚未开放");
    String reason = input.path("reason").asText().trim();
    require(!reason.isBlank() && reason.length() <= 200, "请填写变更原因（不超过200字）");
    var record = load(orderId, false);
    require(record != null && record.path("oId").asLong() > 0, "请先同步确认聚水潭已接单");
    require(record.path("environment").asText().equals(sandbox ? "sandbox" : "production"), "订单环境不一致");
    require(!record.path("busy").asBoolean(), "订单正在处理，请稍后核对");
    require(!record.path("amendment").isObject() || Set.of("COMPLETE", "ABANDONED").contains(record.path("amendment").path("status").asText())
        || (kind.equals("CANCEL") && record.path("amendment").path("status").asText().equals("DRAFT")), "请先完成关联变更单");
    require(!Set.of("PENDING", "UNKNOWN").contains(record.path("change").path("status").asText()), "上次变更尚未核对，请先同步状态");
    require(record.path("editVersion").asText(record.path("updatedAt").asText()).equals(input.path("version").asText()), "订单进度已变化，请关闭后重新打开再操作");
    ObjectNode target = db.object();
    if (kind.equals("ADDRESS")) {
      for (var field : RECIPIENT_FIELDS.entrySet()) {
        String value = input.path("recipient").path(field.getKey()).asText().trim();
        require(!value.isBlank() && value.length() <= (field.getKey().equals("address") ? 200 : 50), "请完整填写收件信息");
        target.put(field.getValue(), value);
      }
      require(target.path("receiver_mobile").asText().matches("[0-9+() -]{7,30}"), "收件电话格式无效");
    } else if (kind.equals("REMARK")) {
      String remark = input.path("remark").asText().trim();
      require(remark.length() <= 400, "发货备注不超过400字");
      target.put("remark", (sandbox ? "【CRM沙箱测试，禁止实际发货】" : "") + "CRM订单：" + orderId + "\n" + remark);
    }
    if (target.has("remark")) require(target.path("remark").asText().length() <= 500, "含订单编号的备注总长度不能超过500字");
    // Fetch authoritative order and outbound records immediately before the mutation.
    var params = db.object().put("shop_id", record.path("request").path("shop_id").asLong());
    params.putArray("so_ids").add(record.path("soId").asText());
    var orders = pages("/open/orders/single/query", params.deepCopy(), "orders");
    require(orders.size() == 1 && orders.get(0).path("o_id").asLong() == record.path("oId").asLong(), "订单已拆分、合并或查询结果不唯一，请人工核对");
    require(sameOrderContents(orders.get(0), record.path("request")), "聚水潭商品或金额与CRM发货快照不一致，可能已合单或人工改动，请先核对");
    String upstreamStatus = orders.get(0).path("status").asText();
    var allowed = kind.equals("CANCEL")
        ? Set.of("WaitPay", "Delivering", "Question", "WaitConfirm", "WaitFConfirm", "WaitOuterSent")
        : Set.of("WaitPay", "Question", "WaitConfirm", "WaitFConfirm");
    require(allowed.contains(upstreamStatus), "聚水潭当前状态不允许该变更，请先处理仓库流程");
    require(pages("/open/orders/out/simple/query", params.deepCopy(), "datas").isEmpty(), "订单已生成出库记录，请先核对仓库，不能直接变更");
    ObjectNode biz = target.deepCopy();
    String path;
    if (kind.equals("CANCEL")) {
      path = "/open/jushuitan/orderbyoid/cancel";
      biz.putArray("o_ids").add(record.path("oId").asLong());
      biz.put("cancel_type", "不需要了").put("remark", reason);
    } else {
      biz.put("o_id", record.path("oId").asLong());
      if (kind.equals("ADDRESS")) biz.put("shop_id", record.path("request").path("shop_id").asLong());
      path = kind.equals("ADDRESS") ? "/open/jushuitan/orderaddress/update" : "/open/jushuitan/order/remark/upload";
    }
    var change = db.object().put("id", UUID.randomUUID().toString()).put("kind", kind).put("status", "PENDING")
        .put("reason", reason).put("operator", actor.name()).put("createdAt", CrmRepository.now());
    change.set("target", target); change.set("before", record.path("request").deepCopy());
    // Serialize against other CRM instances as well as this service instance.
    tx.executeWithoutResult(s -> {
      db.state(true);
      var current = load(orderId, true);
      require(current != null && current.path("updatedAt").equals(record.path("updatedAt")) && !current.path("busy").asBoolean(), "订单已变化，请重新核对");
      record.set("change", change); record.put("busy", true).put("startedAt", Instant.now().toEpochMilli());
      store(record); db.audit(actor.name(), "聚水潭订单变更：" + orderId + " / " + kind + " / " + reason);
    });
    try {
      var response = client.mutate(change.path("id").asText(), path, biz);
      // Explicit business rejection is final; transport/ambiguous outcomes remain unresolved.
      boolean rejected = (response.has("issuccess") && !response.path("issuccess").asBoolean())
          || response.path("code").asInt(-1) != 0
          || (response.path("data").has("issuccess") && !response.path("data").path("issuccess").asBoolean());
      if (rejected) change.put("status", "REJECTED").put("message", "聚水潭拒绝变更，请检查接口权限和订单状态：" + safe(response.path("msg").asText() + " " + response.path("data").path("msg").asText()));
      else change.put("message", "变更已发送，等待查询核对实际结果");
    } catch (Exception e) {
      change.put("status", "UNKNOWN").put("message", "变更结果待核对，请同步状态，勿重复提交");
    }
    record.put("busy", false); record.set("change", change);
    event(record, change.path("message").asText());
    tx.executeWithoutResult(s -> store(record));
    if (!change.path("status").asText().equals("REJECTED")) return sync(orderId);
    archiveChange(record);
    tx.executeWithoutResult(s -> store(record));
    return publicRecord(record);
  }

  private boolean sameOrderContents(JsonNode actual, JsonNode expected) {
    if (!actual.path("items").isArray() || !actual.hasNonNull("pay_amount")) return false;
    if (actual.path("pay_amount").decimalValue().compareTo(expected.path("pay_amount").decimalValue()) != 0) return false;
    var wanted = new HashMap<String, java.math.BigDecimal>(); var found = new HashMap<String, java.math.BigDecimal>();
    for (var item : expected.path("items")) wanted.merge(item.path("sku_id").asText(), item.path("qty").decimalValue(), java.math.BigDecimal::add);
    for (var item : actual.path("items")) found.merge(item.path("sku_id").asText(), item.path("qty").decimalValue(), java.math.BigDecimal::add);
    return wanted.keySet().equals(found.keySet()) && wanted.entrySet().stream()
        .allMatch(e -> e.getValue().compareTo(found.get(e.getKey())) == 0);
  }

  private void archiveChange(ObjectNode record) {
    var change = (ObjectNode) record.path("change");
    if (!change.path("archived").asBoolean()) {
      change.put("archived", true);
      record.withArray("changes").add(change.deepCopy());
    }
  }

  private void reconcileChange(ObjectNode record, JsonNode orders) {
    if (!record.path("change").isObject() || orders.size() != 1) return;
    var change = (ObjectNode) record.path("change");
    if (!Set.of("PENDING", "UNKNOWN").contains(change.path("status").asText())) return;
    var actual = orders.get(0);
    if (actual.path("o_id").asLong() != record.path("oId").asLong()) return;
    boolean matches = true;
    if (change.path("kind").asText().equals("CANCEL")) matches = actual.path("status").asText().equals("Cancelled");
    else for (var fields = change.path("target").fields(); fields.hasNext();) {
      var field = fields.next();
      matches &= actual.path(field.getKey()).asText().equals(field.getValue().asText());
    }
    if (!matches) {
      change.put("message", "尚未查询到一致的变更结果，请稍后同步；仍不一致时需人工核对，禁止重复提交");
      return;
    }
    if (!change.path("kind").asText().equals("CANCEL"))
      ((ObjectNode) record.path("request")).setAll((ObjectNode) change.path("target"));
    change.put("status", "CONFIRMED").put("confirmedAt", CrmRepository.now()).put("message", "聚水潭已核对，变更生效");
    archiveChange(record); event(record, "聚水潭订单变更已核对生效：" + change.path("kind").asText());
  }

  /** Original CRM order/receipts remain immutable; the linked amendment owns its difference ledger. */
  public synchronized JsonNode amend(String orderId, JsonNode input, Actor actor) {
    actor.require("business.write"); actor.require("order.approve");
    require(client.changesEnabled(), "请先开通订单变更服务");
    var record = load(orderId, false);
    require(record != null && record.path("editVersion").asText(record.path("updatedAt").asText()).equals(input.path("version").asText()), "订单已变化，请重新打开");
    require(!record.path("amendment").isObject() || Set.of("COMPLETE", "ABANDONED").contains(record.path("amendment").path("status").asText()), "已有未完成的关联变更单，请继续处理");
    require(!Set.of("PENDING", "UNKNOWN").contains(record.path("change").path("status").asText()), "上次变更结果待核对");
    String reason = input.path("reason").asText().trim();
    require(!reason.isBlank() && reason.length() <= 160, "请填写变更原因（不超过160字）");
    var state = db.state(false);
    ObjectNode effective = record.path("effectiveOrder").isObject() ? record.path("effectiveOrder").deepCopy() : record.path("snapshot").deepCopy();
    var before = effective.deepCopy();
    effective.set("items", input.path("items").deepCopy());
    java.math.BigDecimal total = java.math.BigDecimal.ZERO;
    try {
      for (var item : effective.path("items")) total = total.add(new java.math.BigDecimal(item.path("qty").asText()).multiply(new java.math.BigDecimal(item.path("price").asText())));
    } catch (Exception e) { throw new ApiException("商品数量或单价无效"); }
    require(total.signum() > 0 && total.compareTo(new java.math.BigDecimal("999999999999.99")) <= 0, "变更后订单金额无效或超限");
    effective.put("total", total).put("paid", total);
    String nextSo = "CRM-" + UUID.randomUUID().toString().replace("-", "");
    var requestInput = db.object().put("shopId", record.path("request").path("shop_id").asLong());
    var recipient = requestInput.putObject("recipient");
    RECIPIENT_FIELDS.forEach((key, value) -> recipient.set(key, record.path("request").path(value)));
    requestInput.set("items", input.path("mappings"));
    var request = JushuitanOrderMapper.build(mapper, effective, state.path("products"), requestInput, nextSo, sandbox);
    // Validate products/shop before touching the old warehouse order.
    validateSkus(request);
    var delta = total.subtract(new java.math.BigDecimal(before.path("total").asText()));
    var amendment = db.object().put("id", "BG-" + UUID.randomUUID().toString()).put("status", "DRAFT")
        .put("reason", reason).put("operator", actor.name()).put("createdAt", CrmRepository.now())
        .put("oldSoId", record.path("soId").asText()).put("oldOId", record.path("oId").asLong())
        .put("difference", delta).put("transferred", new java.math.BigDecimal(before.path("total").asText())).put("newSoId", nextSo);
    amendment.set("before", before); amendment.set("effectiveOrder", effective); amendment.set("request", request);
    tx.executeWithoutResult(s -> {
      db.state(true); var current = load(orderId, true);
      require(current != null && current.path("updatedAt").equals(record.path("updatedAt")) && !current.path("busy").asBoolean(), "订单已变化，请重试");
      record.set("amendment", amendment); store(record);
      db.audit(actor.name(), "创建关联变更单：" + amendment.path("id").asText() + " / " + orderId);
    });
    return cancelForAmendment(orderId, actor);
  }

  private JsonNode cancelForAmendment(String orderId, Actor actor) {
    var record = load(orderId, false);
    var amendment = (ObjectNode) record.path("amendment");
    var input = db.object().put("kind", "CANCEL").put("version", record.path("editVersion").asText(record.path("updatedAt").asText()))
        .put("reason", "关联变更单 " + amendment.path("id").asText());
    var result = change(orderId, input, actor);
    record = load(orderId, false); amendment = (ObjectNode) record.path("amendment");
    if (result.path("change").path("status").asText().equals("CONFIRMED"))
      amendment.put("status", amendment.path("difference").decimalValue().signum() == 0 ? "READY" : "AWAITING_FUNDS");
    else if (!result.path("change").path("status").asText().equals("REJECTED")) amendment.put("status", "WAIT_CANCEL");
    var saved = record; tx.executeWithoutResult(s -> store(saved));
    return publicRecord(record);
  }

  public synchronized JsonNode continueAmendment(String orderId, JsonNode input, Actor actor) {
    actor.require("business.write"); actor.require("order.approve");
    require(client.changesEnabled(), "请先开通订单变更服务");
    var record = load(orderId, false);
    require(record != null && record.path("amendment").isObject(), "没有关联变更单");
    require(record.path("environment").asText().equals(sandbox ? "sandbox" : "production"), "订单环境不一致");
    require(!record.path("busy").asBoolean(), "正在处理，请先同步核对");
    var amendment = (ObjectNode) record.path("amendment");
    reconcileCancelledAmendment(record);
    String stage = amendment.path("status").asText();
    require(!Set.of("COMPLETE", "ABANDONED").contains(stage), "变更单已结束");
    if (input.path("abandon").asBoolean()) {
      require(stage.equals("DRAFT") && !Set.of("PENDING", "UNKNOWN").contains(record.path("change").path("status").asText()), "已取消或结果待核对的变更单不能放弃");
      amendment.put("status", "ABANDONED"); var abandoned = record;
      tx.executeWithoutResult(s -> { store(abandoned); db.audit(actor.name(), "放弃关联变更单：" + abandoned.path("amendment").path("id").asText()); });
      return publicRecord(record);
    }
    if (stage.equals("DRAFT")) return cancelForAmendment(orderId, actor);
    if (stage.equals("WAIT_CANCEL")) return sync(orderId);
    // Reconcile a previous upload before any retry. Identity and payload never change.
    if (Set.of("UNKNOWN", "SUBMITTING").contains(stage)) {
      sync(orderId);
      record = load(orderId, false);
      if (record.path("amendment").path("status").asText().equals("COMPLETE")) return publicRecord(record);
      amendment = (ObjectNode) record.path("amendment");
      require(!record.has("syncError") && !amendment.path("conflict").asBoolean(), "新单查询失败或内容不一致，请人工核对，不能重试推送");
      require(Instant.now().toEpochMilli() - amendment.path("startedAt").asLong() > 300000, "推送结果待核对，至少五分钟后再核对并重试原单号");
    }
    // Always recheck the old identity; new shipment must never coexist with a live old order.
    var params = db.object().put("shop_id", record.path("request").path("shop_id").asLong());
    params.putArray("so_ids").add(amendment.path("oldSoId").asText());
    var oldOrders = pages("/open/orders/single/query", params.deepCopy(), "orders");
    require(oldOrders.size() == 1 && oldOrders.get(0).path("o_id").asLong() == amendment.path("oldOId").asLong()
        && oldOrders.get(0).path("status").asText().equals("Cancelled"), "旧订单尚未确认取消，禁止推送新单");
    require(pages("/open/orders/out/simple/query", params.deepCopy(), "datas").isEmpty(), "旧单有出库记录，需人工核对");
    if (amendment.path("difference").decimalValue().signum() != 0 && !amendment.has("settlement")) {
      actor.require("payment.write");
      java.math.BigDecimal amount;
      try { amount = new java.math.BigDecimal(input.path("amount").asText()); }
      catch (Exception e) { throw new ApiException("请登记已实际完成的补收或退款金额"); }
      require(amount.compareTo(amendment.path("difference").decimalValue().abs()) == 0, "登记金额须与变更差额完全一致");
      String method = input.path("method").asText().trim(), reference = input.path("reference").asText().trim();
      require(!reference.isBlank() && reference.length() <= 100, "请填写银行/支付流水号（不超过100字）");
      require(!method.equals("预收款核销") && !db.rows("SELECT id FROM payment_methods WHERE name=? AND status='启用'", method).isEmpty(), "请选择已启用的收退款方式（暂不支持预收款核销）");
      amendment.set("settlement", db.object().put("id", UUID.randomUUID().toString())
          .put("amount", amendment.path("difference").decimalValue()).put("method", method).put("reference", reference)
          .put("operator", actor.name()).put("time", CrmRepository.now()));
    }
    validateSkus(amendment.path("request").deepCopy());
    var saved = record; var intent = amendment;
    tx.executeWithoutResult(s -> {
      db.state(true); var current = load(orderId, true);
      require(current != null && current.path("updatedAt").equals(saved.path("updatedAt")) && !current.path("busy").asBoolean(), "变更单已由其他操作更新");
      if (intent.has("settlement")) {
        var receipt = (ObjectNode) intent.path("settlement").deepCopy();
        receipt.put("order", orderId).put("customerId", intent.path("effectiveOrder").path("customerId").asText())
            .put("customer", intent.path("effectiveOrder").path("customer").asText()).put("type", "订单变更")
            .put("amendmentId", intent.path("id").asText());
        db.receipt(receipt); // Same stable receipt ID on upload retries; never duplicate the cash entry.
      }
      intent.put("status", "SUBMITTING").put("startedAt", Instant.now().toEpochMilli());
      saved.put("busy", true).put("startedAt", Instant.now().toEpochMilli()); store(saved);
      db.audit(actor.name(), "关联变更单结算并提交：" + intent.path("id").asText());
    });
    try {
      var response = client.upload(mapper.createArrayNode().add(amendment.path("request")));
      JsonNode match = null;
      var rows = response.path("data").path("datas").isArray() ? response.path("data").path("datas") : response.path("datas");
      for (var row : rows) if (row.path("so_id").equals(amendment.path("request").path("so_id"))) match = row;
      if (match != null && match.path("issuccess").asBoolean() && match.path("o_id").asLong() > 0)
        amendment.put("newOId", match.path("o_id").asLong()).put("status", "UNKNOWN").put("message", "新单已接收，等待回查核对");
      else if (match != null && match.has("issuccess") && !match.path("issuccess").asBoolean() && match.path("o_id").asLong() == 0)
        amendment.put("status", "FAILED").put("message", "新单被拒绝，请修复接口权限或商品状态后继续；已登记的差额不会重复记账");
      else amendment.put("status", "UNKNOWN").put("message", "新单结果待核对，禁止另建重复变更单");
    } catch (Exception e) { amendment.put("status", "UNKNOWN").put("message", "新单结果待核对，禁止另建重复变更单"); }
    record.put("busy", false); tx.executeWithoutResult(s -> store(saved));
    return sync(orderId);
  }

  private boolean reconcileCancelledAmendment(ObjectNode record) {
    var amendment = record.path("amendment"); var change = record.path("change");
    if (amendment.isObject() && Set.of("DRAFT", "WAIT_CANCEL").contains(amendment.path("status").asText())
        && change.path("kind").asText().equals("CANCEL") && change.path("status").asText().equals("CONFIRMED")
        && record.path("soId").equals(amendment.path("oldSoId"))
        && record.path("oId").asLong() == amendment.path("oldOId").asLong()
        && change.path("reason").asText().contains(amendment.path("id").asText())) {
      ((ObjectNode) amendment).put("status", amendment.path("difference").decimalValue().signum() == 0 ? "READY" : "AWAITING_FUNDS");
      return true;
    }
    return false;
  }

  private void reconcileAmendment(ObjectNode record) {
    if (!record.path("amendment").isObject()) return;
    var amendment = (ObjectNode) record.path("amendment");
    String stage = amendment.path("status").asText();
    if (reconcileCancelledAmendment(record)) return;
    if (!Set.of("UNKNOWN", "SUBMITTING").contains(stage)) return;
    var params = db.object().put("shop_id", amendment.path("request").path("shop_id").asLong());
    params.putArray("so_ids").add(amendment.path("newSoId").asText());
    var orders = pages("/open/orders/single/query", params, "orders");
    if (orders.isEmpty()) return;
    if (orders.size() != 1) {
      amendment.put("conflict", true).put("message", "新单查询结果不唯一，请人工核对"); return;
    }
    var actual = orders.get(0);
    if (!actual.path("so_id").asText().equals(amendment.path("newSoId").asText()) || actual.path("o_id").asLong() <= 0
        || !Set.of("WaitPay", "WaitConfirm", "WaitFConfirm", "Question", "Delivering", "Sent", "WaitOuterSent").contains(actual.path("status").asText())) return;
    if (!sameOrderContents(actual, amendment.path("request"))) {
      amendment.put("conflict", true).put("message", "新单商品或金额与变更单不一致，请人工核对"); return;
    }
    record.put("soId", amendment.path("newSoId").asText()).put("oId", actual.path("o_id").asLong());
    record.set("request", amendment.path("request").deepCopy()); record.set("effectiveOrder", amendment.path("effectiveOrder").deepCopy());
    record.remove("change");
    amendment.put("status", "COMPLETE").put("newOId", actual.path("o_id").asLong()).put("completedAt", CrmRepository.now())
        .put("message", "旧发货单已取消，差额已登记，新订单已回查生效");
    record.withArray("amendments").add(amendment.deepCopy());
    event(record, "关联变更单已生效：" + amendment.path("id").asText());
  }

  private String safe(String text) { return text.replaceAll("[\\r\\n]", " ").substring(0, Math.min(200, text.length())); }

  private ArrayNode pages(String path, ObjectNode request, String key) {
    ArrayNode rows = mapper.createArrayNode();
    for (int page = 1; page <= 20; page++) {
      request.put("page_index", page).put("page_size", 50);
      var result = data(client.query(path, request));
      for (var row : result.path(key)) rows.add(row);
      if (!result.path("has_next").asBoolean()) return rows;
    }
    throw new ApiException("分页未完成，本次状态未覆盖，请稍后重试");
  }

  public synchronized JsonNode sync(String orderId) {
    ObjectNode record = load(orderId, false);
    require(record != null, "该订单尚未提交聚水潭");
    require(record.path("environment").asText().equals(sandbox ? "sandbox" : "production"), "当前环境与订单绑定环境不一致");
    require(!record.path("busy").asBoolean() || Instant.now().toEpochMilli() - record.path("startedAt").asLong() > 120000,
        "订单正在推送，请稍后同步");
    try {
      reconcileAmendment(record);
      var params = db.object().put("shop_id", record.path("request").path("shop_id").asLong());
      params.putArray("so_ids").add(record.path("soId").asText());
      var orders = pages("/open/orders/single/query", params, "orders");
      if (orders.isEmpty()) {
        if (Instant.now().toEpochMilli() - record.path("startedAt").asLong() > 60000
            && Set.of("UNKNOWN", "SUBMITTING", "FAILED").contains(record.path("status").asText()))
          record.put("status", "FAILED").put("message", "本次查询未找到订单，可沿用原单号重试");
        else record.put("message", "本次查询未找到订单，保留原状态，请稍后再次核对");
      } else {
        reconcileChange(record, orders);
        if (record.path("amendment").path("status").asText().equals("WAIT_CANCEL")) reconcileAmendment(record);
        var outs = pages("/open/orders/out/simple/query", params, "datas");
        record.set("shipments", JushuitanShipmentSummary.shipments(mapper, outs, orders));
        var summary = JushuitanShipmentSummary.status(orders, record.path("shipments"), record.path("request").path("items"));
        record.put("status", summary).put("oId", orders.get(0).path("o_id").asLong());
        String message = summary.equals("EXCEPTION") ? orders.get(0).path("question_desc").asText("聚水潭订单异常") : "已同步聚水潭最新状态";
        record.put("message", safe(message));
      }
      record.put("busy", false).put("lastSyncedAt", CrmRepository.now());
      record.remove("syncError");
      event(record, record.path("message").asText());
    } catch (Exception e) {
      record.put("syncError", e instanceof ApiException ? e.getMessage() : "同步暂未完成，已保留上次状态");
      event(record, record.path("syncError").asText());
    }
    tx.executeWithoutResult(s -> store(record));
    return publicRecord(record);
  }

  @Scheduled(fixedDelayString = "${CRM_JST_POLL_MS:60000}", initialDelay = 30000)
  public void poll() {
    if (!client.configured()) return;
    // Rotate oldest checked records, bounding each polling cycle.
    for (var row : db.rows("SELECT order_id,payload FROM jst_shipments WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.environment'))=? ORDER BY updated_at LIMIT 10", sandbox ? "sandbox" : "production")) {
      var record = db.parse(row.get("payload").toString());
      if (!record.path("environment").asText().equals(sandbox ? "sandbox" : "production")) continue;
      try { sync(row.get("order_id").toString()); } catch (Exception ignored) { /* Next poll reconciles interrupted attempts. */ }
    }
  }
}
