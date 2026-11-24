package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A durable approval-owned outbox; the transport owns ERP request persistence and reconciliation. */
@Service
public class JushuitanCreditPushService {
  public record Submission(String orderId, String soId, String approvalUuid, String instanceCode,
      String environment, JsonNode orderSnapshot, JsonNode products, JsonNode input, String attemptId) {}

  public interface Transport {
    /** Explicit capability gate: an unconfirmed credit-order contract must remain disabled. */
    default boolean ready() { return false; }
    default String unavailableMessage() { return "聚水潭账期推送能力尚未启用，订单审批及真实收款状态已保留"; }
    JsonNode push(Submission submission);
    /** Must query the original identity. It must not blindly create or resend an order. */
    default JsonNode reconcile(Submission submission) { return null; }
  }

  /** Only throw this when no upstream write has been attempted. */
  public static final class Blocked extends RuntimeException {
    public Blocked(String message) { super(message); }
  }

  private static final Logger LOG = LoggerFactory.getLogger(JushuitanCreditPushService.class);
  private static final long LEASE_MS = 120000;
  private static final long RECHECK_MS = 60000;
  private static final String SELECT = "SELECT payload FROM feishu_order_approvals WHERE order_id=?";
  private static final Set<String> ACCEPTED = Set.of("ACCEPTED", "PARTIAL", "SHIPPED", "EXCEPTION", "CANCELLED");
  private final CrmRepository db;
  private final TransactionTemplate tx;
  private final ObjectProvider<Transport> transports;
  private final String environment;

  public JushuitanCreditPushService(CrmRepository db, PlatformTransactionManager manager,
      ObjectProvider<Transport> transports, @Value("${CRM_JST_ENV:sandbox}") String environment) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.transports = transports;
    this.environment = environment;
  }

  public List<ObjectNode> list(Actor actor) {
    actor.require("business.read");
    return db.rows("SELECT payload FROM feishu_order_approvals WHERE JSON_EXTRACT(payload,'$.creditPush') IS NOT NULL ORDER BY updated_at DESC,order_id")
        .stream().map(row -> JushuitanCreditPushPolicy.summary(db.parse(row.get("payload").toString()).path("creditPush"))).toList();
  }

  /** Internal, single-config authorization path. It does not impersonate a CRM user or bypass HTTP authentication. */
  ObjectNode bootstrap(JushuitanCreditPushBootstrap.Authorization authorization, JsonNode instance) {
    return tx.execute(transaction -> {
      var state = db.state(true);
      var rows = db.rows("SELECT status,instance_code,payload FROM feishu_order_approvals WHERE order_id=? FOR UPDATE", authorization.orderId());
      require(rows.size() == 1 && "APPROVED".equals(rows.getFirst().get("status"))
          && authorization.instanceCode().equals(rows.getFirst().get("instance_code")), "单次授权对应的审批记录尚未真实批准");
      var approval = (ObjectNode) db.parse(rows.getFirst().get("payload").toString());
      var order = order(state, authorization.orderId());
      requireApproved(approval, order);
      require(authorization.orderId().equals(approval.path("orderId").asText())
          && authorization.approvalUuid().equals(approval.path("uuid").asText())
          && authorization.instanceCode().equals(approval.path("instanceCode").asText()), "单次授权与当前审批绑定不一致");
      require("APPROVED".equals(instance.path("status").asText())
          && authorization.instanceCode().equals(instance.path("instance_code").asText())
          && authorization.approvalUuid().equalsIgnoreCase(instance.path("uuid").asText())
          && !approval.path("approvalCode").asText().isBlank()
          && approval.path("approvalCode").asText().equals(instance.path("approval_code").asText())
          && !approval.path("openId").asText().isBlank()
          && approval.path("openId").asText().equals(instance.path("open_id").asText()), "单次授权未通过飞书原生审批核验");
      require(JushuitanCreditPushPolicy.sameApprovedContents(approval.path("orderSnapshot"), order), "当前订单与真实审批快照不一致");
      JsonNode shipping = authorization.recipient() == null ? null : JushuitanCreditPushBootstrap.shippingRecipient(authorization.recipient());
      var snapshotDraft = approval.path("orderSnapshot").path("approvalDraft");
      if (shipping != null) require(authorization.expectedOriginalAddress() != null
          && authorization.expectedOriginalAddress().equals(snapshotDraft.path("recipientAddress").asText().strip()), "收货补充授权与原审批地址不一致");
      ObjectNode push;
      boolean existingAuthorization = false;
      // Restarts reuse the original immutable intent even after actual receipts legitimately change paid.
      if (approval.path("creditPush").isObject()) {
        var existing = (ObjectNode) approval.path("creditPush");
        require(authorization.shopId().equals(JushuitanCreditPushPolicy.identifier(existing.path("input").path("shopId")))
            && JushuitanCreditPushPolicy.ACCOUNTING_MODE.equals(existing.path("accountingMode").asText())
            && environment.equals(existing.path("environment").asText()), "既有推单意图与授权店铺、环境或口径不一致");
        if (shipping == null) return JushuitanCreditPushPolicy.summary(existing);
        var previous = existing.path("shippingAddressAuthorization");
        if (previous.isObject()) {
          require(authorization.authorizationId().equals(previous.path("authorizationId").asText())
              && shipping.equals(previous.path("after")) && address(existing.path("input").path("recipient")).equals(shipping),
              "该推单已绑定其他收货补充授权，不能修改冻结资料");
          return JushuitanCreditPushPolicy.summary(existing);
        }
        require(!existing.path("attempted").asBoolean() && !"SUBMITTING".equals(existing.path("status").asText())
            && !existing.hasNonNull("oId"), "推单已尝试发送，不能通过启动授权修改冻结收货资料");
        existingAuthorization = existing.hasNonNull("authorizationId");
        push = existing.deepCopy();
      } else {
        approval.put("creditPushPolicyVersion", JushuitanCreditPushPolicy.VERSION).put("creditPushEnvironment", environment)
            .put("creditPushAccountingMode", JushuitanCreditPushPolicy.ACCOUNTING_MODE);
        var products = JushuitanCreditPushPolicy.products(order, state.path("products"));
        approval.set("creditPushProducts", products);
        push = JushuitanCreditPushPolicy.intent(approval, approval.path("orderSnapshot"), products, environment);
      }
      require(JushuitanCreditPushBootstrap.money(order.path("total")).compareTo(authorization.expectedTotal()) == 0
          && JushuitanCreditPushBootstrap.money(order.path("paid")).compareTo(authorization.expectedPaid()) == 0,
          "当前订单金额已变化，单次补推授权不再适用");
      require(db.rows("SELECT order_id FROM jst_shipments WHERE order_id=?", authorization.orderId()).isEmpty()
          && db.rows("SELECT order_id FROM jst_document_sales WHERE order_id=?", authorization.orderId()).isEmpty(),
          "该订单已有关联聚水潭记录，不能通过单次授权另建订单");
      var details = db.object().put("shopId", authorization.shopId());
      JushuitanCreditPushPolicy.supplement(push, details);
      ObjectNode shippingAuthorization = null;
      if (shipping != null) {
        shippingAuthorization = db.object().put("authorizationId", authorization.authorizationId()).put("authorizedAt", CrmRepository.now());
        shippingAuthorization.set("before", address(push.path("input").path("recipient")));
        shippingAuthorization.set("after", shipping.deepCopy());
        var recipient = (ObjectNode) push.path("input").path("recipient");
        shipping.properties().forEach(field -> recipient.set(field.getKey(), field.getValue().deepCopy()));
        push.set("shippingAddressAuthorization", shippingAuthorization);
        JushuitanCreditPushPolicy.validateIntent(push);
      }
      push.put("authorizationId", authorization.authorizationId()).put("authorizationSource", "EXPLICIT_USER_ONE_ORDER")
          .put("authorizedAt", CrmRepository.now()).put("nextAttemptAt", 0);
      approval.set("creditPush", push); store(approval);
      var audit = db.object().put("type", "JST_CREDIT_ONE_ORDER_AUTHORIZED").put("authorizationId", authorization.authorizationId())
          .put("orderId", authorization.orderId()).put("approvalUuid", authorization.approvalUuid())
          .put("instanceCode", authorization.instanceCode()).put("shopId", authorization.shopId())
          .put("accountingMode", JushuitanCreditPushPolicy.ACCOUNTING_MODE)
          .put("expectedTotal", authorization.expectedTotal().toPlainString()).put("expectedPaid", authorization.expectedPaid().toPlainString());
      if (shippingAuthorization != null) audit.set("shippingAddressAuthorization", shippingAuthorization.deepCopy());
      if (existingAuthorization) audit.put("type", "JST_CREDIT_SHIPPING_AUTHORIZED");
      db.audit("system:explicit-one-order-authorization", "按用户单次明确授权补推账期订单：" + OrderNumbers.display(order), audit);
      return JushuitanCreditPushPolicy.summary(push);
    });
  }

  private static ObjectNode address(JsonNode recipient) {
    var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    for (String field : List.of("province", "city", "district", "address")) result.put(field, recipient.path(field).asText().strip());
    return result;
  }

  /** Explicitly prepare one approved historical order. Never enumerate or backfill approved orders. */
  public ObjectNode prepare(String orderId, JsonNode details, Actor actor) {
    actor.require("business.read"); actor.require("business.write"); actor.require("order.approve");
    require(details.path("creditFulfillment").isBoolean() && details.path("creditFulfillment").booleanValue(), "请明确确认按账期推送聚水潭");
    require(JushuitanCreditPushPolicy.ACCOUNTING_MODE.equals(details.path("accountingMode").asText()), "请明确确认ERP按账期挂账（非实收）放行，CRM实收保持真实");
    var result = tx.execute(transaction -> {
      var state = db.state(true);
      var approval = load(orderId, true);
      var order = order(state, orderId);
      requireApproved(approval, order);
      var old = approval.path("creditPush");
      if (old.isObject() && (old.path("attempted").asBoolean()
          || ACCEPTED.contains(old.path("status").asText()) || "SUBMITTING".equals(old.path("status").asText())))
        return JushuitanCreditPushPolicy.summary(old);
      require(JushuitanCreditPushPolicy.sameApprovedContents(approval.path("orderSnapshot"), order), "当前订单与审批快照不一致，不能补推");
      ObjectNode push;
      if (old.isObject()) push = old.deepCopy();
      else {
        require(db.rows("SELECT order_id FROM jst_shipments WHERE order_id=?", orderId).isEmpty(), "该订单已有聚水潭记录，请通过发货详情核对，不能重复补推");
        require(db.rows("SELECT order_id FROM jst_document_sales WHERE order_id=?", orderId).isEmpty(), "该订单已关联聚水潭销售单，不能重复补推");
        approval.put("creditPushPolicyVersion", JushuitanCreditPushPolicy.VERSION).put("creditPushEnvironment", environment);
        approval.put("creditPushAccountingMode", JushuitanCreditPushPolicy.ACCOUNTING_MODE);
        var products = JushuitanCreditPushPolicy.products(order, state.path("products"));
        approval.set("creditPushProducts", products);
        push = JushuitanCreditPushPolicy.intent(approval, approval.path("orderSnapshot"), products, environment);
      }
      JushuitanCreditPushPolicy.supplement(push, details);
      push.put("updatedAt", CrmRepository.now()).put("nextAttemptAt", 0);
      approval.set("creditPush", push); store(approval);
      db.audit(actor.name(), "补齐账期推单资料：" + OrderNumbers.display(order));
      return JushuitanCreditPushPolicy.summary(push);
    });
    return result;
  }

  public ObjectNode retry(String orderId, Actor actor) {
    actor.require("business.read"); actor.require("business.write"); actor.require("order.approve");
    return tx.execute(transaction -> {
      var state = db.state(true);
      var approval = load(orderId, true);
      requireApproved(approval, order(state, orderId));
      require(approval.path("creditPush").isObject(), "该订单没有账期推单意图，请先确认补推资料");
      var push = (ObjectNode) approval.path("creditPush");
      String status = push.path("status").asText();
      if (ACCEPTED.contains(status) || ("SUBMITTING".equals(status) && push.path("leaseUntil").asLong() > System.currentTimeMillis()))
        return JushuitanCreditPushPolicy.summary(push);
      if ("UNKNOWN".equals(status) || "SUBMITTING".equals(status))
        push.put("status", "UNKNOWN").put("message", "正在核对原聚水潭单号，不会另建订单");
      else {
        JushuitanCreditPushPolicy.validateIntent(push);
        if ("QUEUED".equals(push.path("status").asText())) push.put("message", "等待沿用原单号重试账期推送");
      }
      push.put("nextAttemptAt", 0).put("updatedAt", CrmRepository.now());
      push.remove(List.of("attemptId", "leaseUntil"));
      store(approval);
      return JushuitanCreditPushPolicy.summary(push);
    });
  }

  /** Renew immediately before an upstream write; a stale validator must not send after losing its lease. */
  public boolean renew(Submission submission) {
    return Boolean.TRUE.equals(tx.execute(transaction -> {
      var state = db.state(true);
      var approval = load(submission.orderId(), true);
      if (!matches(approval, submission) || !approved(approval, order(state, submission.orderId()))
          || !environment.equals(submission.environment())
          || !environment.equals(approval.path("creditPush").path("environment").asText())) return false;
      ((ObjectNode) approval.path("creditPush")).put("leaseUntil", System.currentTimeMillis() + LEASE_MS);
      store(approval);
      return true;
    }));
  }

  @Scheduled(initialDelay = 35000, fixedDelayString = "${CRM_JST_CREDIT_PUSH_POLL_MS:15000}")
  void poll() {
    for (var row : db.rows("""
        SELECT order_id FROM feishu_order_approvals WHERE status='APPROVED'
          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.creditPush.status')) IN ('QUEUED','BLOCKED','FAILED','UNKNOWN','SUBMITTING')
          AND COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.creditPush.nextAttemptAt')) AS UNSIGNED),0)<=?
        ORDER BY updated_at,order_id LIMIT 10
        """, System.currentTimeMillis())) {
      try { process(row.get("order_id").toString()); }
      catch (RuntimeException failure) { LOG.warn("Unable to process credit push for order {} ({})", row.get("order_id"), failure.getClass().getSimpleName()); }
    }
  }

  void process(String orderId) {
    Transport selected = null;
    boolean available = false;
    String unavailableMessage = "聚水潭账期推送尚未配置，订单审批及真实收款状态已保留";
    try {
      selected = transports.getIfAvailable();
      available = selected != null && selected.ready();
      if (selected != null) unavailableMessage = selected.unavailableMessage();
    } catch (RuntimeException failure) {
      unavailableMessage = "账期推送能力暂不可用，审批结果已保留，请稍后核对";
    }
    final Transport transport = selected;
    final boolean ready = available;
    final String unavailable = unavailableMessage;
    var claim = tx.execute(transaction -> {
      var state = db.state(true);
      var approval = load(orderId, true);
      if (approval == null || !approval.path("creditPush").isObject()) return null;
      var push = (ObjectNode) approval.path("creditPush");
      if (!approved(approval, order(state, orderId)) || !environment.equals(push.path("environment").asText())) {
        JushuitanCreditPushPolicy.block(push, "订单审批或环境已变化，账期推送已停止，请人工核对");
        push.put("nextAttemptAt", Long.MAX_VALUE); store(approval); return null;
      }
      var linked = db.rows("SELECT payload FROM jst_shipments WHERE order_id=?", orderId);
      if (!linked.isEmpty() && !push.path("soId").asText().equals(db.parse(linked.getFirst().get("payload").toString()).path("soId").asText())) {
        JushuitanCreditPushPolicy.block(push, "该订单已有其他聚水潭提交记录，请核对现有发货详情，不能另建订单");
        push.put("nextAttemptAt", Long.MAX_VALUE); store(approval); return null;
      }
      String status = push.path("status").asText();
      if (ACCEPTED.contains(status) || push.path("nextAttemptAt").asLong() > System.currentTimeMillis()
          || ("SUBMITTING".equals(status) && push.path("leaseUntil").asLong() > System.currentTimeMillis())) return null;
      boolean reconcile = "UNKNOWN".equals(status) || "SUBMITTING".equals(status);
      if (!ready) {
        if (!reconcile) JushuitanCreditPushPolicy.block(push, safe(unavailable));
        else push.put("status", "UNKNOWN").put("message", "原推单结果待核对；账期接口暂不可用，不会另建订单");
        push.put("nextAttemptAt", System.currentTimeMillis() + RECHECK_MS); store(approval); return null;
      }
      if (!reconcile) {
        JushuitanCreditPushPolicy.validateIntent(push);
        if ("BLOCKED".equals(push.path("status").asText())) {
          push.put("nextAttemptAt", System.currentTimeMillis() + RECHECK_MS); store(approval); return null;
        }
      }
      String attempt = UUID.randomUUID().toString();
      push.put("status", "SUBMITTING").put("attemptId", attempt).put("leaseUntil", System.currentTimeMillis() + LEASE_MS)
          .put("attempts", push.path("attempts").asInt() + 1).put("updatedAt", CrmRepository.now());
      if (!reconcile) push.put("attempted", true);
      store(approval);
      return new Claim(submission(push, attempt), reconcile);
    });
    if (claim == null) return;
    try {
      var result = claim.reconcile() ? transport.reconcile(claim.submission()) : transport.push(claim.submission());
      finish(claim.submission(), result, null);
    } catch (Blocked failure) {
      finish(claim.submission(), null, claim.reconcile() ? new IllegalStateException("原推单结果仍待核对，不会另建订单") : failure);
    }
    catch (RuntimeException failure) {
      finish(claim.submission(), null, new IllegalStateException("推单结果暂未确认，请核对原单号；不会另建订单"));
    }
  }

  private void finish(Submission submission, JsonNode result, RuntimeException failure) {
    tx.executeWithoutResult(transaction -> {
      db.state(true);
      var approval = load(submission.orderId(), true);
      if (!matches(approval, submission)) return;
      var push = (ObjectNode) approval.path("creditPush");
      String status = result == null ? "UNKNOWN" : result.path("status").asText("UNKNOWN");
      boolean identity = result == null || ((!result.hasNonNull("soId") || submission.soId().equals(result.path("soId").asText()))
          && (!result.hasNonNull("orderId") || submission.orderId().equals(result.path("orderId").asText())));
      if (!identity) status = "UNKNOWN";
      if (failure instanceof Blocked) {
        status = "BLOCKED";
        push.put("attempted", false);
      } else if (ACCEPTED.contains(status)) {
        if (submission.soId().equals(result.path("soId").asText()) && result.path("oId").isIntegralNumber()
            && result.path("oId").canConvertToLong() && result.path("oId").asLong() > 0) push.set("oId", result.path("oId"));
        else status = "UNKNOWN";
      } else if (!Set.of("FAILED", "UNKNOWN", "BLOCKED").contains(status)) status = "UNKNOWN";
      if ("BLOCKED".equals(status) && push.path("attempted").asBoolean()) status = "UNKNOWN";
      if ("FAILED".equals(status) && result != null && result.has("upstreamAttempted") && !result.path("upstreamAttempted").asBoolean())
        push.put("attempted", false);
      push.put("status", status).put("updatedAt", CrmRepository.now()).put("nextAttemptAt", System.currentTimeMillis() + RECHECK_MS);
      String message = failure == null ? result == null ? "原聚水潭推单结果待核对，不会另建订单" : result.path("message").asText()
          : failure.getMessage();
      if (message == null) message = "";
      if (!identity) message = "聚水潭回传单号与原意图不一致，请核对原单；不会另建订单";
      push.put("message", safe(message.isBlank() ? ACCEPTED.contains(status) ? "聚水潭已接收账期订单" : "原推单结果待核对，请稍后同步" : message));
      push.remove(List.of("attemptId", "leaseUntil"));
      // A definite failure requires an explicit retry; UNKNOWN is reconciled, never resent blindly.
      if ("FAILED".equals(status)) push.put("nextAttemptAt", Long.MAX_VALUE);
      store(approval);
    });
  }

  private Submission submission(JsonNode push, String attempt) {
    return new Submission(push.path("orderId").asText(), push.path("soId").asText(), push.path("approvalUuid").asText(),
        push.path("instanceCode").asText(), push.path("environment").asText(), push.path("orderSnapshot").deepCopy(),
        push.path("products").deepCopy(), push.path("input").deepCopy(), attempt);
  }

  private boolean matches(JsonNode approval, Submission submission) {
    return approval != null && "APPROVED".equals(approval.path("status").asText())
        && submission.approvalUuid().equals(approval.path("uuid").asText())
        && submission.instanceCode().equals(approval.path("instanceCode").asText())
        && submission.attemptId().equals(approval.path("creditPush").path("attemptId").asText())
        && submission.soId().equals(approval.path("creditPush").path("soId").asText())
        && "SUBMITTING".equals(approval.path("creditPush").path("status").asText());
  }

  private boolean approved(JsonNode approval, JsonNode order) {
    return approval != null && approval.path("creditPushPolicyVersion").asInt() == JushuitanCreditPushPolicy.VERSION
        && "APPROVED".equals(approval.path("status").asText()) && !approval.path("uuid").asText().isBlank()
        && !approval.path("instanceCode").asText().isBlank() && JushuitanCreditPushPolicy.ordinary(order)
        && "已批准".equals(order.path("status").asText())
        && approval.path("creditPush").path("policyVersion").asInt() == JushuitanCreditPushPolicy.VERSION
        && JushuitanCreditPushPolicy.ACCOUNTING_MODE.equals(approval.path("creditPushAccountingMode").asText())
        && JushuitanCreditPushPolicy.ACCOUNTING_MODE.equals(approval.path("creditPush").path("accountingMode").asText())
        && approval.path("orderId").asText().equals(approval.path("creditPush").path("orderId").asText())
        && approval.path("creditPush").path("soId").asText().matches("CRM-[a-f0-9]{32}")
        && JushuitanCreditPushPolicy.sameApprovedContents(approval.path("creditPush").path("orderSnapshot"), order)
        && approval.path("uuid").asText().equals(approval.path("creditPush").path("approvalUuid").asText())
        && approval.path("instanceCode").asText().equals(approval.path("creditPush").path("instanceCode").asText());
  }

  private void requireApproved(JsonNode approval, JsonNode order) {
    require(approval != null && "APPROVED".equals(approval.path("status").asText())
        && !approval.path("instanceCode").asText().isBlank() && !approval.path("uuid").asText().isBlank()
        && JushuitanCreditPushPolicy.ordinary(order) && "已批准".equals(order.path("status").asText()),
        "仅飞书已批准的普通销售订单可按账期推送");
  }

  private JsonNode order(JsonNode state, String orderId) {
    for (var row : state.path("orders")) if (orderId.equals(row.path("id").asText())) return row;
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private ObjectNode load(String orderId, boolean lock) {
    var rows = db.rows(SELECT + (lock ? " FOR UPDATE" : ""), orderId);
    return rows.isEmpty() ? null : (ObjectNode) db.parse(rows.getFirst().get("payload").toString());
  }

  private void store(ObjectNode approval) {
    db.jdbc().update("UPDATE feishu_order_approvals SET payload=?,updated_at=CURRENT_TIMESTAMP(3) WHERE order_id=? AND status='APPROVED' AND instance_code=?",
        db.encode(approval), approval.path("orderId").asText(), approval.path("instanceCode").asText());
  }

  private static String safe(String text) {
    return text == null ? "账期推送暂未完成，请核对原单" : text.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(400, text.length()));
  }

  private record Claim(Submission submission, boolean reconcile) {}
}
