package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A receipt approval owns a payment allocation, never a sales-order approval or shipment. */
@Service
public class FeishuReceiptApprovalService {
  private static final Logger LOG = LoggerFactory.getLogger(FeishuReceiptApprovalService.class);
  private static final long LEASE_MS = 120000;
  private static final String SELECT = "SELECT payload FROM feishu_receipt_approvals WHERE id=?";
  private static final Set<String> COMPLETED = Set.of("APPROVED", "REJECTED", "CANCELED", "DELETED");
  private final CrmRepository db;
  private final FeishuApprovalClient client;
  private final ObjectMapper mapper;
  private final BusinessService business;
  private final TransactionTemplate tx;

  public FeishuReceiptApprovalService(CrmRepository db, FeishuApprovalClient client, ObjectMapper mapper,
      BusinessService business, PlatformTransactionManager manager) {
    this.db = db; this.client = client; this.mapper = mapper; this.business = business;
    this.tx = new TransactionTemplate(manager);
  }

  public ObjectNode config(Actor actor) {
    actor.require("business.read");
    var result = db.object().put("enabled", client.receiptEnabled()).put("required", client.receiptEnabled())
        .put("canSubmit", false);
    var categories = result.putArray("receiptCategories");
    if (!client.receiptEnabled()) return result;
    if (!client.receiptReady()) return result.put("message", "收款飞书审批尚未配置完成");
    try {
      var definition = client.definition(client.receiptApprovalCode());
      JsonNode form = definition.path("form");
      if (form.isTextual()) form = mapper.readTree(form.asText());
      for (var field : form) if ("收款类别".equals(field.path("name").asText()))
        for (var option : field.path("option")) {
          String text = option.path("text").asText();
          if (!text.isBlank()) categories.add(text);
        }
      result.put("canSubmit", canSubmitReceipt(actor) && !openId(actor).isBlank());
    } catch (Exception failure) {
      result.put("message", failure instanceof ApiException ? failure.getMessage() : "读取收款审批模板失败，请稍后重试");
    }
    return result;
  }

  public List<ObjectNode> list(Actor actor) {
    actor.require("business.read");
    return db.rows("SELECT payload FROM feishu_receipt_approvals ORDER BY updated_at DESC,id DESC")
        .stream().map(row -> publicRecord(db.parse(row.get("payload").toString()), actor)).toList();
  }

  public ObjectNode create(JsonNode input, Actor actor) {
    String openId = submitter(actor);
    String id = input.path("requestId").asText();
    require(id.matches("[A-Za-z0-9_-]{1,64}"), "收款申请编号无效，请重新打开收款表单");
    var prior = load(id, false);
    if (prior != null) {
      requireInitiator(prior, openId);
      return response(prior, actor);
    }
    require(client.receiptReady(), "收款飞书审批尚未配置完成");
    String code = client.receiptApprovalCode();
    var definition = client.definition(code);
    for (var node : definition.path("node_list"))
      require(!node.path("need_approver").asBoolean(), "收款审批需在飞书配置固定审批人后再提交");
    var claim = tx.execute(status -> {
      var state = db.state(true);
      var existing = load(id, true);
      if (existing != null) {
        requireInitiator(existing, openId);
        return new Claim(existing, "", false);
      }
      if (!input.path("revision").equals(state.path("revision")))
        throw new ApiException(409, "业务数据已更新，请刷新后重新核对本次收款");
      var record = prepare(id, input, business.state(), actor).put("approvalCode", code)
          .put("openId", openId).put("uuid", UUID.randomUUID().toString());
      record.set("form", FeishuReceiptApprovalForm.build(mapper, definition, record, openId));
      var evidence = FeishuApprovalEvidence.plan(mapper, definition, record.path("attachments"));
      if (evidence != null) record.set(FeishuApprovalEvidence.FIELD, evidence);
      db.audit(actor.name(), "发起飞书收款审批：" + id);
      return claim(record);
    });
    return response(claim.attempt().isEmpty() ? claim.record() : dispatch(claim), actor);
  }

  private ObjectNode prepare(String id, JsonNode input, JsonNode state, Actor actor) {
    String customerId = input.path("customerId").asText();
    JsonNode customer = null;
    for (var entry : state.path("customers")) if (customerId.equals(entry.path("id").asText())) customer = entry;
    require(customer != null && !customerId.isBlank(), "请选择有效客户");
    String method = input.path("method").asText();
    require("预收款核销".equals(method) || !db.rows("SELECT id FROM payment_methods WHERE name=? AND status='启用'", method).isEmpty(),
        "请选择已启用的收款方式");
    var amount = money(input.path("amount"));
    var allocations = input.path("allocations");
    require(allocations.isArray() && !allocations.isEmpty() && allocations.size() <= 100, "请选择1至100笔收款订单");
    var orders = index(state.path("orders"));
    var products = index(state.path("products"));
    var reserved = new HashSet<String>();
    BigDecimal reservedCredit = BigDecimal.ZERO;
    for (var row : db.rows("""
        SELECT payload FROM feishu_receipt_approvals WHERE status IN ('QUEUED','SUBMITTING','PENDING','UNKNOWN')
          OR (status='APPROVED' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.booked')),'false')<>'true')
        """)) {
      var reservedBatch = db.parse(row.get("payload").toString());
      for (var allocation : reservedBatch.path("allocations")) reserved.add(allocation.path("orderId").asText());
      if ("预收款核销".equals(reservedBatch.path("method").asText())
          && customerId.equals(reservedBatch.path("customerId").asText()))
        reservedCredit = reservedCredit.add(decimal(reservedBatch.path("amount")));
    }
    if ("预收款核销".equals(method))
      require(amount.compareTo(new BigDecimal(customer.path("balance").asText("0")).subtract(reservedCredit)) <= 0,
          "客户预收款余额不足，或余额已被其他待处理核销审批占用");
    var record = db.object().put("id", id).put("customerId", customerId)
        .put("customer", customer.path("name").asText()).put("amount", amount).put("method", method)
        .put("receiptDate", input.path("receiptDate").asText()).put("receiptCategory", input.path("receiptCategory").asText())
        .put("operator", actor.name()).put("operatorRole", actor.role()).put("createdAt", CrmRepository.now()).put("booked", false);
    String note = input.path("note").asText();
    require(note.length() <= 2000, "收款备注不能超过2000字");
    record.put("note", note);
    if (input.hasNonNull("attachments")) record.set("attachments", business.receiptEvidence(input.path("attachments")));
    var profiles = db.rows("SELECT display_name FROM user_profiles WHERE name=?", actor.name());
    record.put("operatorDisplayName", profiles.isEmpty() ? actor.name() : profiles.getFirst().get("display_name").toString());
    var lines = record.putArray("allocations");
    var seen = new HashSet<String>();
    var sum = BigDecimal.ZERO;
    for (var allocation : allocations) {
      String orderId = allocation.path("orderId").asText();
      var order = orders.get(orderId);
      require(seen.add(orderId) && order != null && !order.hasNonNull("deletedAt")
          && "已批准".equals(order.path("status").asText())
          && customerId.equals(order.path("customerId").asText()), "收款订单不存在、未批准或客户不一致");
      if (reserved.contains(orderId)) throw new ApiException(409, "订单 " + orderId + " 已有未完成的收款审批，请先核对原申请");
      var allocated = money(allocation.path("amount"));
      var paid = order.path("paid").isMissingNode() || order.path("paid").isNull() ? BigDecimal.ZERO : decimal(order.path("paid"));
      require(!"预收款核销".equals(method) || (!"预收款订单".equals(order.path("type").asText())
          && !JushuitanDocumentService.isSettlement(order)), "预收款及结算补录不能使用预收款核销");
      if (JushuitanDocumentService.isSettlement(order))
        JushuitanSettlementService.receivedOn(input.path("receiptDate").asText(), order);
      var execution = BusinessService.receiptExecutionView(db, order);
      var unpaid = decimal(execution.path("total")).subtract(decimal(execution.path("paid")));
      require(paid.compareTo(decimal(execution.path("paid"))) == 0
          && decimal(order.path("total")).compareTo(decimal(execution.path("total"))) == 0,
          "订单有执行金额差异，请通过原变更差额核对及收退款入口处理");
      require(allocated.compareTo(unpaid) <= 0, "本次付款不能超过订单未收金额");
      sum = sum.add(allocated);
      var entry = lines.addObject().put("orderId", orderId).put("amount", allocated);
      entry.set("orderSnapshot", order.deepCopy());
      entry.set("executionSnapshot", execution.deepCopy());
      var items = entry.putArray("items");
      for (var item : execution.path("items")) {
        var product = products.get(item.path("product").asText());
        if (product == null) product = item;
        require(!product.path("name").asText().isBlank(), "订单商品不存在，请先核对产品资料");
        var snapshot = items.addObject().put("product", item.path("product").asText())
            .put("name", product.path("name").asText()).put("model", product.path("model").asText())
            .put("sku", product.path("jstSku").asText().isBlank() ? product.path("code").asText() : product.path("jstSku").asText());
        snapshot.set("qty", item.path("qty").deepCopy()); snapshot.set("price", item.path("price").deepCopy());
      }
    }
    require(sum.compareTo(amount) == 0, "实际收款金额与分配到各订单的金额合计不一致");
    return record;
  }

  public ObjectNode sync(String id, Actor actor) {
    actor.require("business.read");
    return response(refresh(required(id)), actor);
  }

  public ObjectNode retry(String id, Actor actor) {
    String openId = submitter(actor);
    var record = refresh(required(id));
    requireInitiator(record, openId);
    var claim = tx.execute(status -> {
      db.state(true);
      var current = requiredLocked(id);
      requireInitiator(current, openId);
      if (!recoverable(current) && !FeishuApprovalEvidence.retryable(current)) return new Claim(current, "", false);
      require(current.path("form").isArray(), "原收款审批表单缺失，请先核对飞书审批");
      if (FeishuApprovalEvidence.retryable(current)) requireEvidenceRetry(current, actor);
      return claim(current);
    });
    return response(claim.attempt().isEmpty() ? claim.record() : dispatch(claim), actor);
  }

  private Claim claim(ObjectNode record) {
    boolean retry = recoverable(record);
    String attempt = UUID.randomUUID().toString();
    record.put("status", "SUBMITTING").put("attempt", attempt).put("leaseUntil", System.currentTimeMillis() + LEASE_MS);
    record.remove("message"); store(record);
    return new Claim(record, attempt, retry);
  }

  private void requireEvidenceRetry(ObjectNode original, Actor actor) {
    // Failed file preparation released its reservations. Recheck them before resuming that UUID.
    var checked = prepare(original.path("id").asText(), original, business.state(), actor);
    var before = original.path("allocations"); var now = checked.path("allocations");
    require(before.size() == now.size(), "原收款分配已变化，请核对后重新申请");
    for (int i = 0; i < before.size(); i++) {
      require(before.get(i).path("orderId").equals(now.get(i).path("orderId")), "原收款分配已变化，请核对后重新申请");
      for (String field : List.of("id", "type", "customerId", "customer", "owner", "items", "total", "status", "paid", "deletedAt"))
        require(sameEvidenceValue(before.get(i).path("orderSnapshot").path(field), now.get(i).path("orderSnapshot").path(field)),
            "原收款订单或已收金额已变化，请核对后重新申请");
      for (String field : List.of("items", "total", "paid"))
        require(sameEvidenceValue(before.get(i).path("executionSnapshot").path(field), now.get(i).path("executionSnapshot").path(field)),
            "原收款执行金额已变化，请核对后重新申请");
    }
  }

  private static boolean sameEvidenceValue(JsonNode left, JsonNode right) {
    return left.equals((a, b) -> a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, right);
  }

  private ObjectNode dispatch(Claim claim) {
    String instanceCode;
    try {
      FeishuApprovalEvidence.prepare(claim.record(), new AssetService(db), client, value -> evidenceCheckpoint(claim, value));
      FeishuApprovalEvidence.beforeCreate(claim.record(), value -> evidenceCheckpoint(claim, value));
    } catch (ApiException failure) {
      if (!FeishuApprovalEvidence.attempted(claim.record())) return evidenceFailure(claim);
      return dispatchResult(claim, "UNKNOWN", "原收款审批凭证尚待核对，请同步原申请；不会更换凭证", null);
    }
    try {
      instanceCode = client.create(claim.record().path("approvalCode").asText(), claim.record().path("openId").asText(),
          claim.record().path("uuid").asText(), (ArrayNode) claim.record().path("form"));
    } catch (FeishuApprovalClient.Rejected failure) {
      if (claim.retry()) return dispatchResult(claim, "UNKNOWN", "原申请仍待核对，重试未被接受不能证明原审批不存在，请勿重复新建", null);
      return dispatchResult(claim, "FAILED", "飞书未接受收款申请，请核对表单或权限后重新申请", null);
    } catch (FeishuApprovalClient.Uncertain failure) {
      return dispatchResult(claim, "UNKNOWN", "提交结果待核对，请同步状态或重试原申请，勿重复新建收款", null);
    } catch (RuntimeException failure) {
      // Once an outbound attempt starts, an unclassified failure cannot prove that no instance exists.
      return dispatchResult(claim, "UNKNOWN", "收款审批结果待核对，请保留原申请并同步状态", null);
    }
    return dispatchResult(claim, "PENDING", null, instanceCode);
  }

  private void evidenceCheckpoint(Claim claim, ObjectNode prepared) {
    tx.executeWithoutResult(transaction -> {
      db.state(true);
      var current = requiredLocked(claim.record().path("id").asText());
      require(claim.attempt().equals(current.path("attempt").asText()) && "SUBMITTING".equals(current.path("status").asText()),
          "凭证上传申请已变化，请刷新核对原申请");
      if (FeishuApprovalEvidence.attempted(current)) {
        require(current.path("form").equals(prepared.path("form")), "原收款审批表单已冻结，不能在重试时更换凭证或金额");
      } else {
        prepared.set("form", db.parse(db.encode(prepared.path("form"))));
      }
      current.set(FeishuApprovalEvidence.FIELD, prepared.path(FeishuApprovalEvidence.FIELD).deepCopy());
      current.set("form", prepared.path("form").deepCopy());
      current.put("leaseUntil", System.currentTimeMillis() + LEASE_MS);
      store(current);
    });
  }

  private ObjectNode evidenceFailure(Claim claim) {
    return tx.execute(transaction -> {
      db.state(true);
      var current = requiredLocked(claim.record().path("id").asText());
      if (!claim.attempt().equals(current.path("attempt").asText())) return current;
      require(!FeishuApprovalEvidence.attempted(current), "原收款审批已提交，请同步核对");
      ((ObjectNode)current.path(FeishuApprovalEvidence.FIELD)).put("preparationFailed", true);
      store(current);
      return dispatchResult(claim, "FAILED", "凭证尚未上传至飞书，审批未发起；图片已保存在CRM，可重试原申请", null);
    });
  }

  private ObjectNode dispatchResult(Claim claim, String status, String message, String instanceCode) {
    return tx.execute(transaction -> {
      db.state(true);
      var current = requiredLocked(claim.record().path("id").asText());
      if (!claim.attempt().equals(current.path("attempt").asText()) || !"SUBMITTING".equals(current.path("status").asText()))
        return current;
      current.put("status", status); current.remove(List.of("attempt", "leaseUntil"));
      if (instanceCode != null) current.put("instanceCode", instanceCode);
      if (message != null) current.put("message", message); else current.remove("message");
      if (!"UNKNOWN".equals(status) && !FeishuApprovalEvidence.retryable(current)) current.remove("form");
      store(current); return current;
    });
  }

  private ObjectNode refresh(ObjectNode record) {
    if ("APPROVED".equals(record.path("status").asText())) return book(record);
    boolean recovering = recoverable(record);
    if (!recovering && !"PENDING".equals(record.path("status").asText())) return record;
    JsonNode instance;
    try {
      instance = matchingInstance(record, recovering);
    } catch (FeishuApprovalClient.NotFound missing) {
      if (!recovering) throw missing;
      return record;
    }
    var updated = applyInstance(record, instance, recovering);
    return "APPROVED".equals(updated.path("status").asText()) ? book(updated) : updated;
  }

  private JsonNode matchingInstance(JsonNode record, boolean recovering) {
    require(client.identityReady(), "飞书审批应用尚未配置完成");
    var instance = client.instance(record.path(recovering ? "uuid" : "instanceCode").asText());
    require(!instance.path("instance_code").asText().isBlank()
        && (recovering || record.path("instanceCode").asText().equalsIgnoreCase(instance.path("instance_code").asText()))
        && record.path("approvalCode").asText().equalsIgnoreCase(instance.path("approval_code").asText())
        && (!instance.hasNonNull("open_id") || instance.path("open_id").asText().isEmpty()
            || record.path("openId").asText().equals(instance.path("open_id").asText()))
        && (!instance.hasNonNull("uuid") || instance.path("uuid").asText().isEmpty()
            || record.path("uuid").asText().equalsIgnoreCase(instance.path("uuid").asText()))
        && (!recovering || (record.path("uuid").asText().equalsIgnoreCase(instance.path("uuid").asText())
            && record.path("openId").asText().equals(instance.path("open_id").asText()))),
        "飞书审批实例与 CRM 收款申请不匹配");
    return instance;
  }

  private ObjectNode applyInstance(ObjectNode expected, JsonNode instance, boolean recovering) {
    return tx.execute(transaction -> {
      db.state(true);
      var current = requiredLocked(expected.path("id").asText());
      if (!current.path("uuid").equals(expected.path("uuid"))) return current;
      if (recovering ? !recoverable(current) : !"PENDING".equals(current.path("status").asText())) return current;
      String result = instance.path("status").asText();
      current.put("instanceCode", instance.path("instance_code").asText()).put("status", COMPLETED.contains(result) ? result : "PENDING");
      current.remove(List.of("attempt", "leaseUntil", "form", "message"));
      store(current);
      if (COMPLETED.contains(result)) db.audit("飞书审批", "收款审批状态：" + result + "，申请：" + current.path("id").asText());
      return current;
    });
  }

  private ObjectNode book(ObjectNode record) {
    if (record.path("booked").asBoolean()) return record;
    String id = record.path("id").asText();
    try {
      return tx.execute(transaction -> {
        db.state(true);
        var current = requiredLocked(id);
        if (!"APPROVED".equals(current.path("status").asText()) || current.path("booked").asBoolean()) return current;
        business.applyApprovedReceipt(current);
        current.put("booked", true).put("bookedAt", CrmRepository.now());
        current.remove("bookingError"); store(current); return current;
      });
    } catch (RuntimeException failure) {
      LOG.warn("Unable to book approved receipt {} ({})", id, failure.getClass().getSimpleName());
      return tx.execute(transaction -> {
        db.state(true);
        var current = requiredLocked(id);
        if (!current.path("booked").asBoolean() && "APPROVED".equals(current.path("status").asText())) {
          current.put("bookingError", failure instanceof ApiException ? failure.getMessage() : "收款入账暂未完成，请同步状态重试并联系管理员核对");
          store(current);
        }
        return current;
      });
    }
  }

  public ObjectNode cancel(String id, Actor actor) {
    String openId = submitter(actor);
    var prior = required(id);
    requireInitiator(prior, openId);
    refresh(prior);
    var claim = tx.execute(transaction -> {
      db.state(true);
      var record = requiredLocked(id); requireInitiator(record, openId);
      if ("CANCELED".equals(record.path("status").asText())) return new Claim(record, "", false);
      if (!"PENDING".equals(record.path("status").asText()))
        throw new ApiException(409, "仅审批中的收款可撤回；结果待核对时请先同步原申请");
      if (record.path("cancelLeaseUntil").asLong() > System.currentTimeMillis())
        throw new ApiException(409, "正在撤回收款审批，请稍后同步状态");
      String attempt = UUID.randomUUID().toString();
      record.put("cancelAttempt", attempt).put("cancelLeaseUntil", System.currentTimeMillis() + LEASE_MS);
      store(record); return new Claim(record, attempt, false);
    });
    if (claim.attempt().isEmpty()) return response(claim.record(), actor);
    try {
      var instance = matchingInstance(claim.record(), false);
      if ("PENDING".equals(instance.path("status").asText())) {
        client.cancel(claim.record().path("approvalCode").asText(), claim.record().path("instanceCode").asText(), openId);
        instance = matchingInstance(claim.record(), false);
      }
      var updated = applyInstance(claim.record(), instance, false);
      if ("APPROVED".equals(updated.path("status").asText())) book(updated);
      if (!"CANCELED".equals(updated.path("status").asText()))
        throw new ApiException(409, "审批未撤回，状态已变化，请同步收款审批后核对");
      return response(updated, actor);
    } finally {
      tx.executeWithoutResult(transaction -> {
        db.state(true); var current = requiredLocked(id);
        if (claim.attempt().equals(current.path("cancelAttempt").asText())) {
          current.remove(List.of("cancelAttempt", "cancelLeaseUntil")); store(current);
        }
      });
    }
  }

  public ObjectNode flow(String id, Actor actor) {
    actor.require("business.read");
    var record = required(id);
    if (record.path("instanceCode").asText().isBlank())
      return FeishuSalesApprovalFlow.empty(mapper, recoverable(record) ? "UNKNOWN" : record.path("status").asText());
    var instance = matchingInstance(record, false);
    var names = new HashMap<String, String>();
    for (var row : db.rows("SELECT i.open_id,p.display_name FROM feishu_identities i JOIN user_profiles p ON p.name=i.user_name WHERE i.app_id=?", client.appId()))
      names.put(row.get("open_id").toString(), row.get("display_name").toString());
    Set<String> participants = new HashSet<>();
    collectParticipants(instance, participants);
    names.putAll(client.userNames(participants));
    return FeishuSalesApprovalFlow.build(mapper, instance, names, task -> {
      var preview = client.previewAfter(record.path("instanceCode").asText(),
          task.path("id").asText(), task.path("open_id").asText());
      if (preview != null) {
        Set<String> future = new HashSet<>();
        for (var node : preview) for (var user : node.path("user_id_list")) future.add(user.asText());
        names.putAll(client.userNames(future));
      }
      return preview;
    });
  }

  @Scheduled(initialDelay = 45000, fixedDelay = 60000)
  void poll() {
    if (!client.identityReady()) return;
    for (var row : db.rows("""
        SELECT payload FROM feishu_receipt_approvals WHERE status IN ('PENDING','UNKNOWN')
          OR (status='SUBMITTING' AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.leaseUntil')) AS UNSIGNED)<=?)
          OR (status='APPROVED' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.booked')),'false')<>'true')
        ORDER BY updated_at,id LIMIT 10
        """, System.currentTimeMillis())) {
      var record = (ObjectNode) db.parse(row.get("payload").toString());
      try { refresh(record); }
      catch (RuntimeException failure) { LOG.warn("Unable to refresh receipt approval {} ({})", record.path("id").asText(), failure.getClass().getSimpleName()); }
      // Rotate unresolved records so one broken upstream instance cannot starve later approvals.
      db.jdbc().update("UPDATE feishu_receipt_approvals SET updated_at=CURRENT_TIMESTAMP(3) WHERE id=?", record.path("id").asText());
    }
  }

  private ObjectNode publicRecord(JsonNode record, Actor actor) {
    var result = db.object();
    for (String field : List.of("id", "status", "amount", "method", "customerId", "customer", "receiptDate", "receiptCategory",
        "operator", "createdAt", "updatedAt", "instanceCode", "message", "booked", "bookedAt", "bookingError", "note", "attachments"))
      if (record.has(field)) result.set(field, record.path(field).deepCopy());
    if (recoverable(record)) result.put("status", "UNKNOWN");
    if (!record.path("operatorDisplayName").asText().isBlank())
      result.put("operator", record.path("operatorDisplayName").asText());
    var lines = result.putArray("allocations");
    for (var allocation : record.path("allocations")) {
      var line = lines.addObject().put("orderId", allocation.path("orderId").asText());
      line.set("amount", allocation.path("amount")); line.set("items", allocation.path("items").deepCopy());
    }
    boolean initiator = canSubmitReceipt(actor)
        && client.identityReady() && openId(actor).equals(record.path("openId").asText());
    result.put("canCancel", initiator && "PENDING".equals(record.path("status").asText()));
    result.put("canRetryOriginal", initiator && (recoverable(record) || FeishuApprovalEvidence.retryable(record)) && record.path("form").isArray());
    return result;
  }

  private ObjectNode response(JsonNode record, Actor actor) {
    var result = db.object(); result.set("approval", publicRecord(record, actor)); result.set("state", business.state()); return result;
  }

  private String submitter(Actor actor) {
    actor.require("business.read"); actor.require("business.write");
    if (!canSubmitReceipt(actor)) throw new ApiException(403, "无操作权限：发起收款审批");
    require(client.identityReady(), "飞书审批应用尚未配置完成");
    String openId = openId(actor); require(!openId.isBlank(), "请使用已绑定的飞书账号发起收款审批"); return openId;
  }

  private static boolean canSubmitReceipt(Actor actor) {
    return actor.permissions().containsAll(Set.of("business.read", "business.write", "payment.write"));
  }

  private static void collectParticipants(JsonNode node, Set<String> ids) {
    if (node.isObject()) {
      if (!node.path("open_id").asText().isBlank()) ids.add(node.path("open_id").asText());
      node.elements().forEachRemaining(child -> collectParticipants(child, ids));
    } else if (node.isArray()) node.forEach(child -> collectParticipants(child, ids));
  }

  private String openId(Actor actor) {
    var rows = db.rows("SELECT open_id FROM feishu_identities WHERE app_id=? AND user_name=?", client.appId(), actor.name());
    return rows.isEmpty() ? "" : rows.getFirst().get("open_id").toString();
  }

  private static void requireInitiator(JsonNode record, String openId) {
    if (!openId.equals(record.path("openId").asText())) throw new ApiException(403, "请由原发起人处理这笔收款审批");
  }

  private ObjectNode required(String id) {
    var record = load(id, false); if (record == null) throw new ApiException(404, "未找到收款审批"); return record;
  }
  private ObjectNode requiredLocked(String id) {
    var record = load(id, true); if (record == null) throw new ApiException(404, "未找到收款审批"); return record;
  }
  private ObjectNode load(String id, boolean lock) {
    var rows = db.rows(SELECT + (lock ? " FOR UPDATE" : ""), id);
    return rows.isEmpty() ? null : (ObjectNode) db.parse(rows.getFirst().get("payload").toString());
  }
  private void store(ObjectNode record) {
    record.put("updatedAt", CrmRepository.now());
    String id = record.path("id").asText();
    String instance = record.path("instanceCode").asText().isBlank() ? null : record.path("instanceCode").asText();
    if (load(id, false) == null) {
      db.jdbc().update("INSERT INTO feishu_receipt_approvals(id,status,instance_code,payload) VALUES(?,?,?,?)",
          id, record.path("status").asText(), instance, db.encode(record));
    } else {
      db.jdbc().update("UPDATE feishu_receipt_approvals SET status=?,instance_code=?,payload=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
          record.path("status").asText(), instance, db.encode(record), id);
    }
  }
  private static boolean recoverable(JsonNode record) {
    return "UNKNOWN".equals(record.path("status").asText()) || ("SUBMITTING".equals(record.path("status").asText())
        && record.path("leaseUntil").asLong() <= System.currentTimeMillis());
  }
  private static Map<String, JsonNode> index(JsonNode values) {
    var index = new HashMap<String, JsonNode>(); values.forEach(value -> index.put(value.path("id").asText(), value)); return index;
  }
  private static BigDecimal money(JsonNode value) {
    var result = decimal(value);
    require(result.signum() > 0 && result.compareTo(new BigDecimal("999999999999.99")) <= 0 && result.stripTrailingZeros().scale() <= 2,
        "收款金额须大于零且最多两位小数"); return result;
  }
  private static BigDecimal decimal(JsonNode value) {
    try {
      require((value.isTextual() || value.isNumber()) && value.asText().length() <= 64, "金额格式无效");
      return new BigDecimal(value.asText());
    } catch (NumberFormatException failure) { throw new ApiException("金额格式无效"); }
  }
  private record Claim(ObjectNode record, String attempt, boolean retry) {}
}
