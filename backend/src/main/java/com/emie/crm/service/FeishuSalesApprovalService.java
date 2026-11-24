package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;
import static com.emie.crm.service.BusinessService.CANCELED_ORDER;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FeishuSalesApprovalService {
  private static final Logger LOG = LoggerFactory.getLogger(FeishuSalesApprovalService.class);
  private static final String SELECT = "SELECT payload FROM feishu_order_approvals WHERE order_id=?";
  private static final String SUBMISSION_CHANGED = "飞书审批状态已变化，请刷新订单后重试";
  private static final long SUBMISSION_LEASE_MS = 120000;
  private static final Set<String> SUBMITTABLE = Set.of("草稿", "审批中");
  private static final Set<String> APPROVAL_TYPES = Set.of("销售订单", "海外提货单", "预收款订单");
  private static final Set<String> COMPLETED = Set.of("APPROVED", "REJECTED", "CANCELED", "DELETED");
  private static final Set<String> RESUBMITTABLE = Set.of("FAILED", "REJECTED", "CANCELED", "DELETED");
  private static final Set<String> NO_ACTIVE_APPROVAL = Set.of("QUEUED", "FAILED", "REJECTED", "CANCELED", "DELETED");
  private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

  private final CrmRepository db;
  private final FeishuApprovalClient client;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final BusinessService business;
  private final AsyncTaskExecutor executor;
  private final JushuitanService warehouseDirectory;
  @Value("${CRM_JST_ENV:sandbox}")
  private String jstEnvironment = "sandbox";
  private final Map<String, Readiness> readiness = new java.util.concurrent.ConcurrentHashMap<>();

  public FeishuSalesApprovalService(CrmRepository db, FeishuApprovalClient client, ObjectMapper mapper,
      PlatformTransactionManager manager, BusinessService business,
      @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
    this(db, client, mapper, manager, business, executor, null);
  }

  @Autowired
  public FeishuSalesApprovalService(CrmRepository db, FeishuApprovalClient client, ObjectMapper mapper,
      PlatformTransactionManager manager, BusinessService business,
      @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor, JushuitanService warehouseDirectory) {
    this.db = db;
    this.client = client;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(manager);
    this.business = business;
    this.executor = executor;
    this.warehouseDirectory = warehouseDirectory;
  }

  public Map<String, Object> config(Actor actor) {
    actor.require("business.read");
    boolean canSubmit = client.identityReady() && actor.permissions().contains("business.write") && !openId(actor).isEmpty();
    var sales = readiness("销售订单");
    var prepayment = readiness("预收款订单");
    Map<String, Object> result = new HashMap<>();
    result.put("required", true);
    result.put("enabled", client.enabled());
    result.put("canSubmit", canSubmit && (sales.ready() || prepayment.ready()));
    result.put("salesEnabled", client.enabled("销售订单"));
    result.put("salesReady", sales.ready());
    result.put("salesCanSubmit", canSubmit && sales.ready());
    result.put("salesMessage", sales.message());
    result.put("salesRecipientRequired", sales.ready() && FeishuSalesApprovalForm.recipientRequired(mapper, sales.definition()));
    result.put("salesDiscountRequired", sales.ready() && FeishuSalesApprovalForm.discountRequired(mapper, sales.definition()));
    result.put("prepaymentEnabled", client.enabled("预收款订单"));
    result.put("prepaymentReady", prepayment.ready());
    result.put("prepaymentCanSubmit", canSubmit && prepayment.ready());
    result.put("prepaymentMessage", prepayment.message());
    boolean receiptForm = prepayment.ready() && FeishuSalesApprovalForm.usesDedicatedPrepaymentForm(mapper, prepayment.definition());
    result.put("prepaymentReceiptForm", receiptForm);
    result.put("prepaymentReceiptCategories", receiptForm
        ? FeishuSalesApprovalForm.prepaymentCategories(mapper, prepayment.definition()) : List.of());
    return result;
  }

  private Readiness readiness(String type) {
    if (!client.enabled(type)) return new Readiness(false, type + "飞书审批模板尚未配置", 0, null);
    if (!client.identityReady()) return new Readiness(false, "飞书审批应用尚未配置完成", 0, null);
    var cached = readiness.get(type);
    if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached;
    Readiness status;
    try {
      var definition = client.definition(client.approvalCode(type));
      FeishuSalesApprovalForm.usesDedicatedPrepaymentForm(mapper, definition);
      status = new Readiness(true, "", System.currentTimeMillis() + 60000, definition);
    } catch (ApiException failure) {
      status = new Readiness(false, failure.getMessage(), System.currentTimeMillis() + 60000, null);
    }
    readiness.put(type, status);
    return status;
  }

  public List<ObjectNode> list(Actor actor) {
    actor.require("business.read");
    String openId = actor.permissions().contains("business.write") && client.identityReady() ? openId(actor) : "";
    var orders = new HashMap<String, JsonNode>();
    for (var order : db.state(false).path("orders")) orders.put(order.path("id").asText(), order);
    boolean writer = actor.permissions().contains("business.write");
    return db.rows("SELECT payload FROM feishu_order_approvals ORDER BY order_id").stream().map(row -> {
      var record = db.parse(row.get("payload").toString());
      var result = publicRecord(record);
      var order = orders.get(record.path("orderId").asText());
      boolean stoppable = order != null && !order.hasNonNull("deletedAt") && !JushuitanDocumentService.isSettlement(order)
          && APPROVAL_TYPES.contains(order.path("type").asText()) && BusinessService.canStopFinancially(db, order);
      String status = record.path("status").asText();
      boolean initiator = !openId.isBlank() && openId.equals(record.path("openId").asText());
      result.put("canCancel", writer && stoppable && SUBMITTABLE.contains(order.path("status").asText())
          && (NO_ACTIVE_APPROVAL.contains(status) || (initiator && ("PENDING".equals(status) || recoverable(record)))));
      result.put("canDelete", writer && stoppable && Set.of("销售订单", "海外提货单").contains(order.path("type").asText())
          && Set.of("草稿", "审批中", CANCELED_ORDER).contains(order.path("status").asText())
          && (Set.of("REJECTED", "CANCELED", "DELETED").contains(status) || ("FAILED".equals(status) && !hasInstance(record)))
          && (!hasInstance(record) || CANCELED_ORDER.equals(order.path("status").asText())));
      if (!openId.isBlank() && openId.equals(record.path("openId").asText()) && recoverable(record)
          && record.path("form").isArray() && !record.path("uuid").asText().isBlank())
        result.put("canRetryOriginal", true).put("originalVersion", record.path("uuid").asText());
      return result;
    }).toList();
  }

  /** The order snapshot and durable queue entry commit together, before any external request. */
  public ObjectNode createAndSubmit(String orderId, JsonNode input, Actor actor) {
    String openId = submissionOpenId(actor);
    var existing = load(orderId, false);
    if (reuseSubmission(existing, false)) {
      var current = db.state(false);
      requireSameCreation(current, existing, input, openId, orderId);
      return result(current, existing);
    }
    requireSubmissionVersion(existing, input.path("approval"), false);
    var requestedOrder = approvalOrder(input.path("data"), orderId);
    var requestId = requestedOrder.path("approvalRequestId");
    require(requestId.isTextual() && !requestId.asText().isBlank() && requestId.asText().length() <= 128,
        "审批提交请求编号缺失，请刷新订单后重新提交");
    String orderType = requestedOrder.path("type").asText();
    var warehouse = OrderWarehouse.resolve(requestedOrder, input.path("approval"), warehouseDirectory);
    ObjectNode submissionInput = input.deepCopy();
    OrderWarehouse.apply(approvalOrder(submissionInput.path("data"), orderId), warehouse);
    submissionInput.set("approval", OrderWarehouse.approval(input.path("approval"), warehouse));
    String approvalCode = configuredCode(orderType);
    JsonNode definition = client.definition(approvalCode);
    var result = tx.execute(transaction -> {
      var priorState = db.state(true);
      var previous = load(orderId, true);
      if (reuseSubmission(previous, false)) {
        requireSameCreation(priorState, previous, input, openId, orderId);
        return result(priorState, previous);
      }
      requireSubmissionVersion(previous, input.path("approval"), false);
      for (var prior : priorState.path("orders"))
        if (orderId.equals(prior.path("id").asText()))
          require(APPROVAL_TYPES.contains(prior.path("type").asText()) && SUBMITTABLE.contains(prior.path("status").asText()),
              "仅草稿或待审批的订单可提交飞书审批");
      JsonNode state = business.saveForFeishuApproval(submissionInput, actor, orderId);
      var order = approvalOrder(state, orderId);
      require(orderType.equals(order.path("type").asText()), "订单类型已更新，请刷新后重新发起审批");
      require("审批中".equals(order.path("status").asText()), "订单须提交后才能自动发起飞书审批");
      var record = newSubmission(openId, approvalCode, definition, order, state.path("products"), submissionInput.path("approval"), actor)
          .put("status", "QUEUED").put("automatic", true);
      preserveHistory(previous, record);
      store(record);
      return result(state, record);
    });
    dispatchQueued(orderId);
    return result;
  }

  void dispatchQueued(String orderId) {
    try {
      executor.execute(() -> {
        try { dispatchAutomatic(orderId); }
        catch (RuntimeException failure) {
          LOG.warn("Unable to send queued Feishu approval for order {}: {}", orderId, failure.getClass().getSimpleName());
        }
      });
    } catch (TaskRejectedException failure) {
      LOG.warn("Queued Feishu approval for order {} will be sent by the scheduler", orderId);
    }
  }

  record SettlementPreparation(String openId, String approvalCode, JsonNode definition) {}

  SettlementPreparation prepareSettlement(Actor actor) {
    String openId = submissionOpenId(actor);
    String code = configuredCode("销售订单");
    return new SettlementPreparation(openId, code, client.definition(code));
  }

  ObjectNode existingSettlement(String orderId, String requestId, Actor actor) {
    return existingSettlement(db.state(false), orderId, requestId, submissionOpenId(actor));
  }

  ObjectNode existingSettlement(JsonNode state, String orderId, String requestId, String openId) {
    for (var order : state.path("orders")) {
      if (!orderId.equals(order.path("id").asText())) continue;
      var record = load(orderId, false);
      require(JushuitanDocumentService.isSettlement(order) && requestId.equals(order.path("approvalRequestId").asText())
          && record != null && openId.equals(record.path("openId").asText()),
          "结算补录提交编号已被使用，请刷新后重新提交");
      return result(state, record);
    }
    return null;
  }

  /** The settlement creator owns the transaction containing the order, ERP links and approval queue. */
  ObjectNode queueSettlement(JsonNode state, JsonNode order, JsonNode input, Actor actor, SettlementPreparation prepared) {
    var record = newSubmission(prepared.openId(), prepared.approvalCode(), prepared.definition(), order,
        state.path("products"), input, actor).put("status", "QUEUED").put("automatic", true);
    store(record);
    return result(state, record);
  }

  private void requireSameCreation(JsonNode state, JsonNode approval, JsonNode input, String openId, String orderId) {
    String requestedId = "", persistedId = "";
    for (var order : input.path("data").path("orders"))
      if (orderId.equals(order.path("id").asText())) requestedId = order.path("approvalRequestId").asText();
    for (var order : state.path("orders"))
      if (orderId.equals(order.path("id").asText())) persistedId = order.path("approvalRequestId").asText();
    if (requestedId.isBlank() || !requestedId.equals(persistedId) || !openId.equals(approval.path("openId").asText()))
      throw new ApiException(409, "订单编号已被另一笔提交使用，请刷新订单后重新提交");
  }

  private ObjectNode result(JsonNode state, JsonNode approval) {
    var result = db.object();
    result.set("state", state);
    result.set("approval", publicRecord(approval));
    return result;
  }

  private String submissionOpenId(Actor actor) {
    actor.require("business.read");
    actor.require("business.write");
    require(client.identityReady(), "尚未配置飞书审批应用");
    String openId = openId(actor);
    require(!openId.isEmpty(), "请用已绑定的飞书账号发起审批");
    return openId;
  }

  private String configuredCode(String orderType) {
    require(client.ready(orderType), orderType + "飞书审批尚未配置完成");
    return client.approvalCode(orderType);
  }

  public ObjectNode submit(String orderId, JsonNode input, Actor actor) {
    String openId = submissionOpenId(actor);
    boolean retryOriginal = input.path("retryOriginal").booleanValue();
    var existing = load(orderId, false);
    var requestedOrder = approvalOrder(db.state(false), orderId);
    String orderType = requestedOrder.path("type").asText();
    String approvalCode = null;
    JsonNode definition;
    OrderWarehouse.Selection warehouse = null;
    if (reuseSubmission(existing, retryOriginal)) {
      definition = null;
    } else {
      requireSubmissionVersion(existing, input, retryOriginal);
      if (!retryOriginal) warehouse = OrderWarehouse.resolve(requestedOrder, input, warehouseDirectory);
      approvalCode = retryOriginal ? null : configuredCode(orderType);
      definition = retryOriginal ? null : client.definition(approvalCode);
    }
    final String selectedCode = approvalCode;
    final OrderWarehouse.Selection selectedWarehouse = warehouse;
    var claim = tx.execute(transaction -> {
      var state = db.state(true);
      var order = approvalOrder(state, orderId);
      var record = load(orderId, true);
      if (reuseSubmission(record, retryOriginal)) return new Submission(record, "", false);
      requireSubmissionVersion(record, input, retryOriginal);
      require(SUBMITTABLE.contains(order.path("status").asText()), "仅草稿或待审批的订单可发起飞书审批");

      if (retryOriginal) {
        require(record.path("openId").asText().equals(openId), "请由原发起人重试这笔飞书审批");
        require(!record.path("approvalCode").asText().isBlank(), "原审批模板信息缺失，请先核对飞书审批状态");
        require(record.path("form").isArray(), "原审批提交信息缺失，请先核对飞书审批状态");
        return claim(record, true);
      }
      if (definition == null) throw new ApiException(409, SUBMISSION_CHANGED);
      require(orderType.equals(order.path("type").asText()), "订单类型已更新，请刷新后重新发起审批");
      require(!input.has("orderUpdated") || (input.path("orderUpdated").isTextual()
          && input.path("orderUpdated").asText().equals(order.path("updated").asText())),
          "订单内容已更新，请刷新后重新填写审批信息");
      require(selectedWarehouse != null && OrderWarehouse.planned(order).id().equals(selectedWarehouse.id()),
          "订单中的计划发货仓库已变化，请刷新后重新提交");
      if (selectedWarehouse.selected() && !OrderWarehouse.planned(order).equals(selectedWarehouse)) {
        OrderWarehouse.apply(order, selectedWarehouse);
        order.put("updated", LocalDateTime.now(SHANGHAI).format(ORDER_TIME));
        ((ObjectNode) state).put("revision", state.path("revision").asLong() + 1);
        db.state(state);
      }
      var next = newSubmission(openId, selectedCode, definition, order, state.path("products"),
          OrderWarehouse.approval(input, selectedWarehouse), actor);
      preserveHistory(record, next);
      return claim(next, false);
    });
    if (claim.attempt().isEmpty()) return publicRecord(claim.record());
    return dispatch(claim);
  }

  private ObjectNode newSubmission(String openId, String approvalCode, JsonNode definition, JsonNode order, JsonNode products,
      JsonNode input, Actor actor) {
    String orderId = order.path("id").asText();
    if (JushuitanDocumentService.isSettlement(order)) {
      ObjectNode snapshot = order.deepCopy();
      var sales = snapshot.withObject("settlement").putArray("sales");
      for (var row : db.rows("SELECT shop_id,o_id,so_id,sale_metadata FROM jst_document_sales WHERE environment=? AND order_id=? ORDER BY shop_id,o_id", jstEnvironment, orderId)) {
        Object raw = row.get("sale_metadata");
        ObjectNode sale = raw == null ? db.object() : (ObjectNode) db.parse(raw.toString()).deepCopy();
        sale.put("id", row.get("o_id").toString()).put("shopId", row.get("shop_id").toString())
            .put("soId", row.get("so_id").toString());
        sales.add(sale);
      }
      order = snapshot;
    }
    JsonNode approval = input;
    String ownerOpenId = FeishuSalesApprovalForm.requiresOwnerIdentity(mapper, definition) ? ownerOpenId(order) : "";
    var form = FeishuSalesApprovalForm.build(mapper, definition, order, products, approval, ownerOpenId);
    var record = db.object().put("orderId", orderId).put("orderType", order.path("type").asText()).put("approvalCode", approvalCode)
        .put("uuid", UUID.randomUUID().toString()).put("openId", openId).put("createdAt", CrmRepository.now());
    record.set("form", form);
    record.set("orderSnapshot", order.deepCopy());
    db.audit(actor.name(), "发起飞书" + order.path("type").asText() + "审批：" + OrderNumbers.display(order));
    return record;
  }

  private String ownerOpenId(JsonNode order) {
    String owner = order.path("owner").asText().strip();
    var identities = db.rows("""
        SELECT DISTINCT i.open_id FROM feishu_identities i
        JOIN user_profiles p ON p.name=i.user_name
        WHERE i.app_id=? AND p.status='启用' AND (i.user_name=? OR p.display_name=?)
        """, client.appId(), owner, owner);
    if (identities.size() != 1 || !(identities.getFirst().get("open_id") instanceof String openId) || openId.isBlank())
      throw new ApiException(422, "签单人需唯一绑定启用的飞书账号，请核对用户资料后重试");
    return openId;
  }

  private Submission claim(ObjectNode record, boolean retry) {
    String attempt = UUID.randomUUID().toString();
    record.put("status", "SUBMITTING").put("attempt", attempt)
        .put("leaseUntil", System.currentTimeMillis() + SUBMISSION_LEASE_MS);
    record.remove("message");
    store(record);
    return new Submission(record, attempt, retry);
  }

  private void dispatchAutomatic(String orderId) {
    var claim = tx.execute(transaction -> {
      var record = load(orderId, true);
      if (record == null || !record.path("automatic").asBoolean()) return null;
      boolean retry = expired(record);
      if (!retry && !queued(record)) return null;
      return claim(record, retry);
    });
    if (claim != null) dispatch(claim);
  }

  private ObjectNode dispatch(Submission claim) {
    String instanceCode;
    try {
      instanceCode = client.create(claim.record().path("approvalCode").asText(), claim.record().path("openId").asText(), claim.record().path("uuid").asText(),
          (ArrayNode) claim.record().path("form"));
    } catch (FeishuApprovalClient.Rejected failure) {
      return finishSubmission(claim, "", claim.retry() ? "UNKNOWN" : "FAILED", failure.getMessage());
    } catch (FeishuApprovalClient.Uncertain failure) {
      return finishSubmission(claim, "", "UNKNOWN", failure.getMessage());
    } catch (ApiException failure) {
      return finishSubmission(claim, "", claim.retry() ? "UNKNOWN" : "FAILED", failure.getMessage());
    }
    return finishSubmission(claim, instanceCode, "PENDING", "");
  }

  private ObjectNode finishSubmission(Submission claim, String instanceCode, String status, String message) {
    return tx.execute(transaction -> {
      String orderId = claim.record().path("orderId").asText();
      var state = db.state(true);
      var current = load(orderId, true);
      require(current != null, "未找到飞书审批记录");
      if (!claim.attempt().equals(current.path("attempt").asText())) return publicRecord(current);
      current.put("status", status);
      current.remove(List.of("attempt", "leaseUntil"));
      if (!message.isBlank()) current.put("message", message);
      if (!instanceCode.isBlank()) {
        current.put("instanceCode", instanceCode);
        var order = approvalOrder(state, orderId);
        if (order.path("status").asText().equals("草稿")) updateOrderStatus(state, order, "审批中");
      }
      if (status.equals("FAILED")) {
        var order = approvalOrder(state, orderId);
        if (!order.path("status").asText().equals("草稿")) updateOrderStatus(state, order, "草稿");
      }
      if (!status.equals("UNKNOWN") && !status.equals("FAILED")) current.remove("form");
      store(current);
      return publicRecord(current);
    });
  }

  public ObjectNode sync(String orderId, Actor actor) {
    actor.require("business.read");
    var record = load(orderId, false);
    require(record != null, "该订单尚未发起飞书审批");
    return publicRecord(recoverable(record) ? recover(record) : refresh(record));
  }

  public ObjectNode flow(String orderId, Actor actor) {
    actor.require("business.read");
    var order = approvalOrder(db.state(false), orderId);
    if (order.hasNonNull("deletedAt")) throw new ApiException(404, "未找到该订单");
    var record = load(orderId, false);
    if (!hasInstance(record)) {
      String status = record == null ? "NOT_SUBMITTED" : publicRecord(record).path("status").asText("UNKNOWN");
      var flow = FeishuSalesApprovalFlow.empty(mapper, status);
      if (record != null && record.path("message").isTextual()) flow.set("message", record.path("message"));
      return flow;
    }
    var instance = matchingInstance(record);
    Map<String, String> names = new HashMap<>();
    for (var row : db.rows("""
        SELECT i.open_id, p.display_name FROM feishu_identities i
        JOIN user_profiles p ON p.name=i.user_name WHERE i.app_id=?
        """, client.appId())) {
      if (row.get("open_id") instanceof String openId && row.get("display_name") instanceof String displayName)
        names.put(openId, displayName);
    }
    Set<String> participants = new java.util.HashSet<>();
    collectParticipants(instance, participants);
    try { names.putAll(client.userNames(participants)); } catch (ApiException ignored) { }
    String instanceCode = record.path("instanceCode").asText();
    return FeishuSalesApprovalFlow.build(mapper, instance, names, task -> {
      var preview = client.previewAfter(instanceCode, task.path("id").asText(), task.path("open_id").asText());
      if (preview == null || !preview.isArray()) return preview;
      Set<String> future = new java.util.HashSet<>();
      for (var node : preview) for (var id : node.path("user_id_list")) future.add(id.asText());
      try { names.putAll(client.userNames(future)); } catch (ApiException ignored) { }
      return preview;
    });
  }

  public ObjectNode cancel(String orderId, Actor actor) {
    actor.require("business.read");
    actor.require("business.write");
    var existing = load(orderId, false);
    if (recoverable(existing)) {
      cancellationOpenId(existing, actor);
      recover(existing);
    }
    var claim = tx.execute(transaction -> {
      var state = db.state(true);
      var order = approvalOrder(state, orderId);
      var record = load(orderId, true);
      if (CANCELED_ORDER.equals(order.path("status").asText()))
        return new Cancellation(cancelResult(state, record), null, false);
      require(SUBMITTABLE.contains(order.path("status").asText()), "仅草稿或待审批的订单可取消");
      require(unpaid(order), "已有收款的订单不能取消");
      require(BusinessService.canStopFinancially(db, order), "已有财务流水或聚水潭发货、关联单据的订单不能取消");
      if (record == null || NO_ACTIVE_APPROVAL.contains(record.path("status").asText())) {
        if (queued(record)) completeWithdrawal(record);
        cancelOrder(state, order, actor.name());
        return new Cancellation(cancelResult(state, record), null, false);
      }
      if (record.path("status").asText().equals("APPROVED"))
        throw new ApiException(409, "飞书审批已通过，订单无法取消");
      if (!record.path("status").asText().equals("PENDING") || !hasInstance(record))
        throw new ApiException(409, "飞书审批正在发起或结果待核对，请刷新审批状态后重试");
      cancellationOpenId(record, actor);
      if (record.path("cancelLeaseUntil").asLong() > System.currentTimeMillis())
        throw new ApiException(409, "正在撤回飞书审批，请稍后刷新状态");
      boolean previouslyRequested = record.hasNonNull("cancelRequestedBy");
      record.put("cancelRequestedBy", actor.name()).put("cancelAttempt", UUID.randomUUID().toString())
          .put("cancelLeaseUntil", System.currentTimeMillis() + SUBMISSION_LEASE_MS);
      store(record);
      return new Cancellation(null, record, previouslyRequested);
    });
    if (claim.result() != null) return claim.result();

    var record = claim.record();
    boolean withdrawalAttempted = false;
    try {
      var instance = matchingInstance(record);
      String status = instance.path("status").asText();
      if (status.equals("PENDING")) {
        withdrawalAttempted = true;
        client.cancel(record.path("approvalCode").asText(), record.path("instanceCode").asText(),
            record.path("openId").asText());
        instance = matchingInstance(record);
        status = instance.path("status").asText();
      }
      if (!COMPLETED.contains(status)) throw new ApiException(409, "飞书撤回结果待确认，请刷新审批状态");
      applyInstance(record, instance, false);
      if (status.equals("CANCELED")) return canceledOrder(record);
      if (status.equals("APPROVED")) throw new ApiException(409, "飞书审批已通过，订单无法取消");
      throw new ApiException(409, "飞书审批状态已变化，请刷新订单后重试取消");
    } catch (FeishuApprovalClient.WithdrawalDenied failure) {
      withdrawalAttempted = false;
      throw failure;
    } finally {
      releaseCancellation(record, withdrawalAttempted || claim.previouslyRequested());
    }
  }

  private void cancellationOpenId(JsonNode record, Actor actor) {
    require(client.identityReady(), "飞书审批尚未配置完成，需先确认审批状态");
    String openId = openId(actor);
    if (openId.isEmpty() || !openId.equals(record.path("openId").asText()))
      throw new ApiException(403, "请由原发起人使用已绑定的飞书账号取消订单");
  }

  private ObjectNode canceledOrder(ObjectNode record) {
    return tx.execute(transaction -> {
      String orderId = record.path("orderId").asText();
      var state = db.state(true);
      var order = approvalOrder(state, orderId);
      var current = load(orderId, true);
      if (current == null || !record.path("instanceCode").equals(current.path("instanceCode"))
          || !CANCELED_ORDER.equals(order.path("status").asText()))
        throw new ApiException(409, "飞书审批状态已变化，请刷新订单");
      return cancelResult(state, current);
    });
  }

  private void releaseCancellation(ObjectNode record, boolean keepRequest) {
    tx.executeWithoutResult(transaction -> {
      var current = load(record.path("orderId").asText(), true);
      if (current == null || !record.path("cancelAttempt").equals(current.path("cancelAttempt"))) return;
      current.remove(List.of("cancelAttempt", "cancelLeaseUntil"));
      if (!keepRequest) current.remove("cancelRequestedBy");
      store(current);
    });
  }

  private ObjectNode cancelResult(JsonNode state, ObjectNode record) {
    var response = db.object();
    response.set("state", state);
    response.set("approval", record == null ? null : publicRecord(record).put("canCancel", false));
    return response;
  }

  private void cancelOrder(JsonNode state, ObjectNode order, String user) {
    require(BusinessService.canStopFinancially(db, order), "订单已出现财务流水或聚水潭关联，不能取消");
    updateOrderStatus(state, order, CANCELED_ORDER);
    db.audit(user, "取消" + order.path("type").asText(), db.object().put("type", "ORDER_CANCELED").put("source", "CRM")
        .put("orderId", order.path("id").asText()));
  }

  private static boolean unpaid(JsonNode order) {
    try {
      return new BigDecimal(order.path("paid").asText("0")).signum() == 0;
    } catch (NumberFormatException failure) {
      return false;
    }
  }

  private void completeWithdrawal(ObjectNode record) {
    record.put("status", "CANCELED");
    record.remove(List.of("cancelRequestedBy", "cancelAttempt", "cancelLeaseUntil", "form", "message"));
    store(record);
  }

  @Scheduled(initialDelay = 45000, fixedDelay = 60000)
  void poll() {
    if (!client.identityReady()) return;
    for (var row : db.rows("""
        SELECT payload FROM feishu_order_approvals
        WHERE status IN ('QUEUED','PENDING','UNKNOWN')
           OR (status='SUBMITTING' AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.leaseUntil')) AS UNSIGNED) <= ?)
        ORDER BY updated_at,order_id LIMIT 10
        """, System.currentTimeMillis())) {
      var record = (ObjectNode) db.parse(row.get("payload").toString());
      try {
        if (queued(record) || (expired(record) && record.path("automatic").asBoolean()))
          dispatchAutomatic(record.path("orderId").asText());
        else if (recoverable(record)) recover(record);
        else refresh(record);
      } catch (ApiException failure) {
        defer(record);
        LOG.warn("Unable to refresh Feishu approval for order {}: {}", record.path("orderId").asText(), failure.status());
      }
    }
  }

  private ObjectNode recover(ObjectNode record) {
    require(client.identityReady(), "飞书审批尚未配置完成");
    var uuid = record.path("uuid").asText();
    JsonNode instance;
    try {
      instance = client.instance(uuid);
    } catch (FeishuApprovalClient.NotFound ignored) {
      defer(record);
      return record;
    }
    String instanceCode = instance.path("instance_code").asText();
    require(!instanceCode.isBlank() && uuid.equalsIgnoreCase(instance.path("uuid").asText())
        && record.path("approvalCode").asText().equals(instance.path("approval_code").asText())
        && record.path("openId").asText().equals(instance.path("open_id").asText()),
        "飞书审批实例与 CRM 订单不匹配");
    return applyInstance(record, instance, true);
  }

  private ObjectNode refresh(ObjectNode record) {
    if (!record.path("status").asText().equals("PENDING")) return record;
    var instance = matchingInstance(record);
    if (!COMPLETED.contains(instance.path("status").asText())) {
      defer(record);
      return record;
    }
    return applyInstance(record, instance, false);
  }

  private JsonNode matchingInstance(ObjectNode record) {
    require(client.identityReady(), "飞书审批尚未配置完成");
    String instanceCode = record.path("instanceCode").asText();
    JsonNode instance = client.instance(instanceCode);
    require(instanceCode.equals(instance.path("instance_code").asText())
        && record.path("approvalCode").asText().equals(instance.path("approval_code").asText())
        && (!instance.hasNonNull("open_id") || instance.path("open_id").asText().isEmpty()
            || record.path("openId").asText().equals(instance.path("open_id").asText()))
        && (!instance.hasNonNull("uuid") || instance.path("uuid").asText().isEmpty()
            || record.path("uuid").asText().equalsIgnoreCase(instance.path("uuid").asText())),
        "飞书审批实例与 CRM 订单不匹配");
    return instance;
  }

  private ObjectNode applyInstance(ObjectNode record, JsonNode instance, boolean recovered) {
    String instanceCode = instance.path("instance_code").asText();
    String result = instance.path("status").asText();
    return tx.execute(transaction -> {
      String orderId = record.path("orderId").asText();
      var state = db.state(true);
      var current = load(orderId, true);
      require(current != null, "未找到飞书审批记录");
      if (recovered) {
        if (!recoverable(current) || !record.path("uuid").equals(current.path("uuid"))) return current;
        current.put("instanceCode", instanceCode);
        current.remove(List.of("attempt", "leaseUntil", "form", "message"));
      } else if (!instanceCode.equals(current.path("instanceCode").asText())
          || !current.path("status").asText().equals("PENDING")) return current;
      var order = approvalOrder(state, orderId);
      require(SUBMITTABLE.contains(order.path("status").asText()), "CRM 订单状态已变化，请核对后处理");
      boolean completed = COMPLETED.contains(result);
      if (result.equals("CANCELED") && current.hasNonNull("cancelRequestedBy")) {
        if (BusinessService.canStopFinancially(db, order)) {
          cancelOrder(state, order, current.path("cancelRequestedBy").asText());
          completeWithdrawal(current);
          return current;
        }
        current.put("message", "飞书审批已撤回，但订单出现财务流水或聚水潭关联，未取消订单，请核对后处理");
      }
      String orderStatus = result.equals("APPROVED") ? "已批准" : completed ? "草稿" : "审批中";
      if (!order.path("status").asText().equals(orderStatus)) updateOrderStatus(state, order, orderStatus);
      current.put("status", completed ? result : "PENDING");
      if (completed) current.remove(List.of("cancelRequestedBy", "cancelAttempt", "cancelLeaseUntil"));
      store(current);
      if (completed) db.audit("飞书审批", approvalAudit(result, order.path("type").asText()) + "：" + OrderNumbers.display(order));
      return current;
    });
  }

  private static String approvalAudit(String result, String orderType) {
    return orderType + switch (result) {
      case "APPROVED" -> "审批通过";
      case "REJECTED" -> "审批拒绝";
      case "CANCELED" -> "审批撤回";
      default -> "审批已删除";
    };
  }

  private void defer(JsonNode record) {
    db.jdbc().update("""
        UPDATE feishu_order_approvals SET updated_at=CURRENT_TIMESTAMP(3)
        WHERE order_id=? AND instance_code <=> ? AND status=?
        """, record.path("orderId").asText(), hasInstance(record) ? record.path("instanceCode").asText() : null,
        record.path("status").asText());
  }

  private static void collectParticipants(JsonNode node, Set<String> ids) {
    if (node.isObject()) {
      if (!node.path("open_id").asText().isBlank()) ids.add(node.path("open_id").asText());
      node.elements().forEachRemaining(child -> collectParticipants(child, ids));
    } else if (node.isArray()) node.forEach(child -> collectParticipants(child, ids));
  }

  private String openId(Actor actor) {
    var rows = db.rows("SELECT open_id FROM feishu_identities WHERE app_id=? AND user_name=?",
        client.appId(), actor.name());
    return rows.isEmpty() ? "" : rows.getFirst().get("open_id").toString();
  }

  private ObjectNode load(String orderId, boolean lock) {
    var rows = db.rows(SELECT + (lock ? " FOR UPDATE" : ""), orderId);
    return rows.isEmpty() ? null : (ObjectNode) db.parse(rows.getFirst().get("payload").toString());
  }

  private void store(ObjectNode record) {
    record.put("updatedAt", CrmRepository.now());
    db.jdbc().update("""
        INSERT INTO feishu_order_approvals(order_id,status,instance_code,payload) VALUES(?,?,?,?)
        ON DUPLICATE KEY UPDATE status=VALUES(status),instance_code=VALUES(instance_code),
          payload=VALUES(payload),updated_at=CURRENT_TIMESTAMP(3)
        """, record.path("orderId").asText(), record.path("status").asText(),
        hasInstance(record) ? record.path("instanceCode").asText() : null, db.encode(record));
  }

  private static ObjectNode approvalOrder(JsonNode state, String orderId) {
    for (var order : state.path("orders"))
      if (orderId.equals(order.path("id").asText()) && APPROVAL_TYPES.contains(order.path("type").asText())
          && !order.hasNonNull("deletedAt"))
        return (ObjectNode) order;
    throw new ApiException(404, "未找到可发起审批的销售订单、海外提货单或预收款");
  }

  private void updateOrderStatus(JsonNode state, ObjectNode order, String status) {
    var before = order.deepCopy();
    order.put("status", status).put("updated", LocalDateTime.now(SHANGHAI).format(ORDER_TIME));
    ((ObjectNode) state).put("revision", state.path("revision").asLong() + 1);
    db.state(state);
    var detail = db.object().put("type", "ORDER_CHANGE").put("source", "FEISHU_APPROVAL")
        .put("orderId", order.path("id").asText()).put("recordId", order.path("id").asText());
    detail.set("before", before);
    detail.set("after", order.deepCopy());
    detail.putArray("changes").addObject().put("field", "status").set("before", before.path("status"));
    ((ObjectNode) detail.path("changes").get(0)).set("after", order.path("status"));
    db.audit("飞书审批", "同步订单审批状态", detail);
  }

  private void preserveHistory(JsonNode previous, ObjectNode next) {
    if (previous == null) return;
    ArrayNode history = previous.path("history").isArray()
        ? (ArrayNode) previous.path("history").deepCopy() : mapper.createArrayNode();
    var entry = publicRecord(previous);
    entry.remove(List.of("history", "retryVersion"));
    history.add(entry);
    next.set("history", history);
  }

  private ObjectNode publicRecord(JsonNode record) {
    String status = expired(record) ? "UNKNOWN" : record.path("status").asText();
    var result = db.object().put("orderId", record.path("orderId").asText()).put("status", status);
    for (var field : List.of("instanceCode", "message", "createdAt", "updatedAt", "history"))
      if (record.hasNonNull(field)) result.set(field, record.path(field));
    if (record.hasNonNull("cancelRequestedBy")) result.put("cancelPending", true);
    if (RESUBMITTABLE.contains(status) && !record.path("uuid").asText().isBlank())
      result.put("retryVersion", record.path("uuid").asText());
    return result;
  }

  private static boolean hasInstance(JsonNode record) {
    return record != null && !record.path("instanceCode").asText().isEmpty();
  }

  private static boolean queued(JsonNode record) {
    return record != null && record.path("status").asText().equals("QUEUED");
  }

  private static boolean reuseSubmission(JsonNode record, boolean retryOriginal) {
    if (record == null) return false;
    String status = record.path("status").asText();
    return queued(record) || (status.equals("SUBMITTING") && !expired(record))
        || status.equals("PENDING") || status.equals("APPROVED")
        || (!retryOriginal && recoverable(record));
  }

  private static void requireSubmissionVersion(JsonNode record, JsonNode input, boolean retryOriginal) {
    var version = input.path(retryOriginal ? "originalVersion" : "retryVersion");
    if (record == null && !retryOriginal && (version.isMissingNode() || (version.isTextual() && version.asText().isBlank())))
      return;
    if (record != null && (retryOriginal ? recoverable(record) : RESUBMITTABLE.contains(record.path("status").asText()))) {
      String uuid = record.path("uuid").asText();
      if (version.isTextual() && !uuid.isBlank() && uuid.equals(version.asText())) return;
    }
    throw new ApiException(409, SUBMISSION_CHANGED);
  }

  private static boolean expired(JsonNode record) {
    return record.path("status").asText().equals("SUBMITTING")
        && record.path("leaseUntil").asLong() <= System.currentTimeMillis();
  }

  private static boolean recoverable(JsonNode record) {
    return record != null && (record.path("status").asText().equals("UNKNOWN") || expired(record));
  }

  private record Readiness(boolean ready, String message, long expiresAt, JsonNode definition) {}
  private record Submission(ObjectNode record, String attempt, boolean retry) {}
  private record Cancellation(ObjectNode result, ObjectNode record, boolean previouslyRequested) {}
}
