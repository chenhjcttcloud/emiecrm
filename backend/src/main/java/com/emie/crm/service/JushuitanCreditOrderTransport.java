package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists the exact approval-owned ERP tender before writing; ambiguous writes are query-only. */
@Service
public class JushuitanCreditOrderTransport implements JushuitanCreditPushService.Transport {
  private static final String MODE = "ON_ACCOUNT_TENDER";
  private final CrmRepository db;
  private final JushuitanClient client;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final JushuitanCreditPushService queue;
  private final JushuitanService shipments;
  private final String environment;
  private final String accountingMode;

  public JushuitanCreditOrderTransport(CrmRepository db, JushuitanClient client, ObjectMapper mapper,
      PlatformTransactionManager manager, JushuitanCreditPushService queue, JushuitanService shipments,
      @Value("${CRM_JST_ENV:sandbox}") String environment,
      @Value("${CRM_JST_CREDIT_ACCOUNTING_MODE:}") String accountingMode) {
    this.db = db; this.client = client; this.mapper = mapper; this.tx = new TransactionTemplate(manager);
    this.queue = queue; this.shipments = shipments; this.environment = environment; this.accountingMode = accountingMode;
  }

  @Override public boolean ready() {
    return MODE.equals(accountingMode) && Set.of("sandbox", "production").contains(environment)
        && client.configured() && client.uploadsEnabled();
  }

  @Override public String unavailableMessage() {
    return "聚水潭账期挂账（非实收）推送尚未启用或上传连接不可用，CRM真实收款状态已保留";
  }

  @Override public JsonNode push(JushuitanCreditPushService.Submission submission) {
    authorize(submission);
    ObjectNode record;
    Prepared prepared;
    try { prepared = prepare(submission); record = prepared.record(); }
    catch (ApiException failure) { throw new JushuitanCreditPushService.Blocked(failure.getMessage()); }
    // Even another explicit push call cannot repeat an uncertain upstream write.
    if (!prepared.send()) return reconcile(submission);
    try {
      ObjectNode validated = record.path("request").deepCopy();
      shipments.validateCreditRequest(validated);
      if (prepared.frozen() && !validated.equals(record.path("request")))
        throw new ApiException("原订单曾被聚水潭明确拒绝，但商品校验结果已变化；不能修改冻结请求，请人工核对原单");
      if (!prepared.frozen()) record.set("request", canonicalRequest(validated));
      // Human-readable numbers can already belong to another ERP order. Never adopt or overwrite that order.
      JushuitanOrderIdentity.requireUnused(client, mapper, record.path("request"));
    } catch (RuntimeException failure) {
      finish(record, "FAILED", "账期推单资料校验未通过，请核对店铺、仓库及商品", record.path("upstreamAttempted").asBoolean());
      throw new JushuitanCreditPushService.Blocked(failure instanceof ApiException ? failure.getMessage()
          : "聚水潭校验暂不可用，尚未发送账期订单");
    }
    if (!queue.renew(submission)) {
      finish(record, "FAILED", "审批推单任务已变化，本次未发送订单", record.path("upstreamAttempted").asBoolean());
      throw new JushuitanCreditPushService.Blocked("审批推单任务已变化，本次未发送订单");
    }
    // The renewal is after slow read-only validation, immediately before the write boundary.
    record.put("upstreamAttempted", true).put("confirmedRejected", false);
    if (!save(record)) throw new JushuitanCreditPushService.Blocked("推单记录已变化，本次未发送订单");
    try {
      var response = client.upload(mapper.createArrayNode().add(record.path("request").deepCopy()));
      JsonNode match = uploadMatch(response, submission.soId());
      if (response.path("code").asInt(-1) == 0 && match != null && positiveId(match.path("o_id"))
          && match.path("issuccess").isBoolean() && match.path("issuccess").booleanValue()) {
        record.put("oId", match.path("o_id").longValue());
        record.put("nativeStatus", match.path("status").asText("UNKNOWN"));
        finish(record, "ACCEPTED", "聚水潭已接收账期挂账订单，CRM实收仍按实际到账记录", true);
      } else if (response.path("code").asInt(-1) == 0 && match != null
          && match.path("issuccess").isBoolean() && !match.path("issuccess").booleanValue()
          && match.path("o_id").isIntegralNumber() && match.path("o_id").canConvertToLong() && match.path("o_id").longValue() == 0) {
        record.put("confirmedRejected", true);
        finish(record, "FAILED", "聚水潭明确拒绝账期订单，请核对原单及接口数据", true);
      } else finish(record, "UNKNOWN", "账期推单结果待核对，将仅查询原单号，不会重复发送", true);
    } catch (RuntimeException failure) {
      finish(record, "UNKNOWN", "账期推单结果待核对，将仅查询原单号，不会重复发送", true);
    }
    return result(record);
  }

  @Override public JsonNode reconcile(JushuitanCreditPushService.Submission submission) {
    authorize(submission);
    var record = load(submission.orderId(), false);
    if (record == null) return unknown(submission, "缺少原推单记录，请人工核对；不会另建订单");
    requireIdentity(record, submission);
    if (!record.path("request").isObject() || !positiveId(record.path("request").path("shop_id")))
      return unknown(submission, "原店铺信息无效，请人工核对；不会另建订单");
    try {
      var params = mapper.createObjectNode().put("shop_id", record.path("request").path("shop_id").longValue());
      params.putArray("so_ids").add(record.path("soId").asText());
      ArrayNode matches = mapper.createArrayNode();
      for (int page = 1; page <= 20; page++) {
        params.put("page_index", page).put("page_size", 50);
        var response = client.query("/open/orders/single/query", params.deepCopy());
        if (response.path("code").asInt(-1) != 0 || !response.path("data").path("orders").isArray())
          throw new ApiException("原订单查询未成功");
        var data = response.path("data");
        for (var row : data.path("orders")) if (record.path("soId").asText().equals(row.path("so_id").asText())) {
          if (row.hasNonNull("shop_id") && (!positiveId(row.path("shop_id"))
              || row.path("shop_id").longValue() != params.path("shop_id").longValue()))
            throw new ApiException("原订单店铺不一致");
          matches.add(row);
        }
        if (!data.path("has_next").asBoolean()) break;
        if (page == 20) throw new ApiException("原订单查询分页未完成");
      }
      if (matches.size() == 1 && positiveId(matches.get(0).path("o_id"))
          && (!positiveId(record.path("oId")) || record.path("oId").longValue() == matches.get(0).path("o_id").longValue())) {
        var actual = matches.get(0);
        record.put("oId", actual.path("o_id").longValue()).put("nativeStatus", actual.path("status").asText("UNKNOWN"));
        String status = switch (actual.path("status").asText()) {
          case "Sent" -> "SHIPPED";
          case "Question" -> "EXCEPTION";
          case "Cancelled" -> "CANCELLED";
          default -> "ACCEPTED";
        };
        String message = "WaitPay".equals(actual.path("status").asText())
            ? "聚水潭已接收原单，当前仍待付款，请核对ERP账期挂账状态；CRM实收保持真实"
            : "已核对原聚水潭账期订单及最新状态，CRM实收保持真实";
        finish(record, status, message, record.path("upstreamAttempted").asBoolean());
      } else finish(record, "UNKNOWN", "原单未找到或查询结果不唯一，请继续核对；不会重复发送", record.path("upstreamAttempted").asBoolean());
    } catch (RuntimeException failure) {
      finish(record, "UNKNOWN", "原聚水潭订单查询暂未确认，将继续核对原单号；不会重复发送", record.path("upstreamAttempted").asBoolean());
    }
    return result(record);
  }

  private void authorize(JushuitanCreditPushService.Submission submission) {
    if (!ready()) throw new JushuitanCreditPushService.Blocked(unavailableMessage());
    boolean submitted = onSubmission(submission);
    if (!environment.equals(submission.environment()) || !MODE.equals(submission.input().path("accountingMode").asText())
        || submission.orderId().isBlank() || !submission.orderId().equals(submission.orderSnapshot().path("id").asText())
        || !JushuitanOrderIdentity.valid(submission.soId(), submission.orderSnapshot()) || submission.attemptId().isBlank()
        || (submitted ? submission.submissionId().isBlank() : submission.policyVersion() != 1
            || !"APPROVAL_RESULT".equals(submission.trigger())
            || submission.approvalUuid().isBlank() || submission.instanceCode().isBlank()))
      throw new JushuitanCreditPushService.Blocked("提交任务、账期挂账授权或聚水潭环境不一致，未发送订单");
  }

  private static boolean onSubmission(JushuitanCreditPushService.Submission submission) {
    return submission.policyVersion() == 2 && "ORDER_SUBMISSION".equals(submission.trigger());
  }

  private Prepared prepare(JushuitanCreditPushService.Submission submission) {
    return tx.execute(transaction -> {
      db.state(true);
      var record = load(submission.orderId(), true);
      boolean frozen = false;
      if (record != null) {
        requireIdentity(record, submission);
        if (record.path("upstreamAttempted").asBoolean()) {
          if (!record.path("confirmedRejected").asBoolean() || !"FAILED".equals(record.path("status").asText())
              || submission.attemptId().equals(record.path("attemptId").asText())) return new Prepared(record, false, true);
          frozen = true;
        }
      }
      if (!db.rows("SELECT order_id FROM jst_document_sales WHERE order_id=?", submission.orderId()).isEmpty())
        throw new JushuitanCreditPushService.Blocked("订单已有聚水潭销售单关联，不能重复推送");
      if (!frozen && record != null && record.path("request").hasNonNull("lc_id")
          && !JushuitanLogistics.selection(submission.orderSnapshot().path("approvalDraft")).carrier())
        throw new JushuitanCreditPushService.Blocked("原发货请求已指定物流，请核对原单，不能改为由仓库安排后重建");
      var request = frozen ? (ObjectNode)record.path("request").deepCopy()
          : canonicalRequest(onSubmission(submission)
              ? JushuitanOrderMapper.buildSubmittedCredit(mapper, submission.orderSnapshot(), submission.products(),
                  submission.input(), submission.soId(), environment.equals("sandbox"))
              : JushuitanOrderMapper.buildCredit(mapper, submission.orderSnapshot(), submission.products(),
                  submission.input(), submission.soId(), environment.equals("sandbox")));
      boolean fresh = record == null;
      if (fresh) record = mapper.createObjectNode().put("orderId", submission.orderId()).put("soId", submission.soId())
          .put("environment", environment).put("approvalUuid", submission.approvalUuid()).put("instanceCode", submission.instanceCode())
          .put("creditFulfillment", true).put("accountingMode", MODE).put("createdAt", CrmRepository.now())
          .put("operator", onSubmission(submission) ? "订单提交自动推单" : "飞书审批自动推单");
      if (onSubmission(submission)) record.put("policyVersion", 2).put("trigger", "ORDER_SUBMISSION")
          .put("submissionId", submission.submissionId());
      record.put("attemptId", submission.attemptId()).put("transportVersion", UUID.randomUUID().toString())
          .put("status", "SUBMITTING").put("busy", true)
          .put("startedAt", Instant.now().toEpochMilli()).put("updatedAt", CrmRepository.now()).put("upstreamAttempted", frozen);
      record.set("request", request);
      if (!frozen) record.set("snapshot", submission.orderSnapshot().deepCopy());
      record.remove("effectiveOrder");
      event(record, onSubmission(submission) ? "已登记提交订单账期推送，正在校验店铺、仓库及商品；财务在聚水潭审核"
          : "已登记飞书审批账期挂账请求，正在校验店铺、仓库及商品");
      if (fresh) {
        db.jdbc().update("INSERT INTO jst_shipments(order_id,payload) VALUES(?,?)", submission.orderId(), db.encode(record));
        var details = mapper.createObjectNode().put("soId", submission.soId()).put("approvalUuid", submission.approvalUuid())
            .put("instanceCode", submission.instanceCode()).put("accountingMode", MODE).put("noncash", true);
        if (onSubmission(submission)) details.put("trigger", "ORDER_SUBMISSION").put("submissionId", submission.submissionId());
        db.audit("SYSTEM", (onSubmission(submission) ? "订单提交自动账期推送：" : "飞书审批自动账期推送：") + submission.orderId(), details);
      } else db.jdbc().update("UPDATE jst_shipments SET payload=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=?", db.encode(record), submission.orderId());
      return new Prepared(record, true, frozen);
    });
  }

  private void requireIdentity(JsonNode record, JushuitanCreditPushService.Submission submission) {
    if (!submission.orderId().equals(record.path("orderId").asText()) || !submission.soId().equals(record.path("soId").asText())
        || !JushuitanOrderIdentity.valid(record.path("soId").asText(), record.path("snapshot"))
        || (onSubmission(submission)
            ? record.path("policyVersion").asInt() != 2 || !"ORDER_SUBMISSION".equals(record.path("trigger").asText())
                || !submission.submissionId().equals(record.path("submissionId").asText())
            : record.path("policyVersion").asInt(1) != 1 || !submission.approvalUuid().equals(record.path("approvalUuid").asText())
                || !submission.instanceCode().equals(record.path("instanceCode").asText()))
        || !environment.equals(record.path("environment").asText()) || !record.path("creditFulfillment").asBoolean()
        || !MODE.equals(record.path("accountingMode").asText())
        || !submission.soId().equals(record.path("request").path("so_id").asText()))
      throw new JushuitanCreditPushService.Blocked("已有聚水潭记录与本次审批意图不一致，不能另建订单");
  }

  private ObjectNode load(String orderId, boolean lock) {
    var rows = db.rows("SELECT payload FROM jst_shipments WHERE order_id=?" + (lock ? " FOR UPDATE" : ""), orderId);
    return rows.isEmpty() ? null : (ObjectNode)db.parse(rows.getFirst().get("payload").toString());
  }

  /** Merge transport fields only, preserving any concurrently saved warehouse/external history. */
  private boolean save(ObjectNode record) {
    return Boolean.TRUE.equals(tx.execute(transaction -> {
      db.state(true);
      var current = load(record.path("orderId").asText(), true);
      if (current == null || !current.path("soId").equals(record.path("soId"))
          || !current.path("attemptId").equals(record.path("attemptId"))
          || !current.path("transportVersion").equals(record.path("transportVersion"))) return false;
      if (current.path("upstreamAttempted").asBoolean() && !current.path("request").equals(record.path("request"))) return false;
      for (String field : List.of("status", "message", "busy", "upstreamAttempted", "confirmedRejected", "oId", "nativeStatus", "events", "lastSyncedAt", "request"))
        if (record.has(field)) current.set(field, record.path(field).deepCopy());
      String version = UUID.randomUUID().toString();
      current.put("transportVersion", version); record.put("transportVersion", version);
      current.put("updatedAt", CrmRepository.now());
      db.jdbc().update("UPDATE jst_shipments SET payload=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=?", db.encode(current), current.path("orderId").asText());
      return true;
    }));
  }

  private void finish(ObjectNode record, String status, String message, boolean attempted) {
    record.put("status", status).put("message", message).put("busy", false).put("upstreamAttempted", attempted);
    event(record, message); save(record);
  }

  private void event(ObjectNode record, String message) {
    var events = record.withArray("events"); events.addObject().put("time", CrmRepository.now()).put("message", message);
    while (events.size() > 50) events.remove(0);
  }

  private JsonNode uploadMatch(JsonNode response, String soId) {
    JsonNode rows = response.path("data").path("datas");
    if (!rows.isArray()) rows = response.path("datas");
    JsonNode match = null;
    for (var row : rows) if (soId.equals(row.path("so_id").asText())) {
      if (match != null) return null;
      match = row;
    }
    return match;
  }

  private static boolean positiveId(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
  }

  /** Freeze the same JSON number representation used after a database round trip. */
  private ObjectNode canonicalRequest(ObjectNode request) {
    ObjectNode canonical = (ObjectNode)db.parse(db.encode(request));
    boolean exact = request.equals((left, right) -> left.isNumber() && right.isNumber()
        ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right) ? 0 : 1, canonical);
    if (!exact) throw new ApiException("账期请求金额超出存储精度，尚未发送，请人工核对");
    return canonical;
  }

  private ObjectNode result(JsonNode record) {
    var result = mapper.createObjectNode();
    for (String field : List.of("orderId", "soId", "oId", "status", "message", "nativeStatus", "upstreamAttempted"))
      if (record.has(field)) result.set(field, record.path(field).deepCopy());
    return result;
  }

  private ObjectNode unknown(JushuitanCreditPushService.Submission submission, String message) {
    return mapper.createObjectNode().put("orderId", submission.orderId()).put("soId", submission.soId())
        .put("status", "UNKNOWN").put("message", message);
  }

  private record Prepared(ObjectNode record, boolean send, boolean frozen) {}
}
