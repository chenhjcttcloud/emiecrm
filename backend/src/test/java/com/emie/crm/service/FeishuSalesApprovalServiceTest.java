package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class FeishuSalesApprovalServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CrmRepository db = mock(CrmRepository.class);
  private final FeishuApprovalClient client = mock(FeishuApprovalClient.class);
  private final BusinessService business = mock(BusinessService.class);
  private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Actor reader = new Actor("reader", "查看人", Set.of("business.read"));
  private final Actor writer = new Actor("sales", "销售", Set.of("business.read", "business.write"));
  private FeishuSalesApprovalService service;
  private ObjectNode record;
  private ObjectNode state;

  @BeforeEach void prepare() throws Exception {
    when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    when(client.identityReady()).thenReturn(true);
    when(client.appId()).thenReturn("app");
    when(client.ready(anyString())).thenReturn(true);
    when(client.approvalCode(anyString())).thenReturn("template");
    when(db.jdbc()).thenReturn(jdbc);
    when(db.object()).thenAnswer(call -> mapper.createObjectNode());
    when(db.encode(any())).thenAnswer(call -> mapper.writeValueAsString(call.getArgument(0, Object.class)));
    when(db.parse(anyString())).thenAnswer(call -> mapper.readTree(call.getArgument(0, String.class)));
    record = mapper.createObjectNode().put("orderId", "o1").put("approvalCode", "template")
        .put("instanceCode", "instance").put("uuid", "uuid").put("openId", "ou_actor").put("status", "PENDING");
    state = FeishuApprovalFixtures.state(); state.withArray("orders").add(FeishuApprovalFixtures.order());
    when(db.state(anyBoolean())).thenAnswer(call -> state);
    when(db.rows(startsWith("SELECT payload FROM feishu_order_approvals WHERE order_id="), eq("o1")))
        .thenAnswer(call -> record == null ? List.of() : List.of(Map.of("payload", record.toString())));
    when(db.rows(startsWith("SELECT open_id FROM feishu_identities"), eq("app"), eq("sales")))
        .thenReturn(List.of(Map.of("open_id", "ou_actor")));
    when(jdbc.update(startsWith("INSERT INTO feishu_order_approvals"), eq("o1"), anyString(), nullable(String.class), anyString()))
        .thenAnswer(call -> {record = (ObjectNode) mapper.readTree(call.getArgument(4, String.class)); return 1;});
    service = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor);
  }
  @Test void matchesInstanceBeforeApprovingAndUpdatesRevision() {
    when(client.instance("instance")).thenReturn(instance("APPROVED"));
    assertEquals("APPROVED", service.sync("o1", reader).path("status").asText());
    assertEquals("已批准", state.path("orders").get(0).path("status").asText());
    assertEquals(2, state.path("revision").asInt());
    verify(db).state(state);
  }
  @Test void mismatchedTemplateCannotApprove() {
    when(client.instance("instance")).thenReturn(instance("APPROVED").put("approval_code", "other"));
    assertThrows(ApiException.class, () -> service.sync("o1", reader));
    verify(db, never()).state(any(JsonNode.class));
  }
  @ParameterizedTest @ValueSource(strings={"REJECTED","CANCELED","DELETED"})
  void negativeTerminalResultsReturnTheOrderToDraft(String status) {
    when(client.instance("instance")).thenReturn(instance(status));
    assertEquals(status, service.sync("o1", reader).path("status").asText());
    assertEquals("草稿", state.path("orders").get(0).path("status").asText());
  }
  @Test void uncertainSubmissionUsesItsPersistedUuidForRecovery() {
    record.put("status", "UNKNOWN").remove("instanceCode");
    when(client.instance("uuid")).thenReturn(instance("APPROVED"));
    assertEquals("APPROVED", service.sync("o1", reader).path("status").asText());
    verify(client, never()).create(any(), any(), any(), any());
  }
  @ParameterizedTest @ValueSource(strings={"QUEUED","SUBMITTING","PENDING","UNKNOWN","APPROVED"})
  void repeatedSaveAndSubmitReturnsTheExistingSubmission(String status) {
    record.put("status", status).put("leaseUntil", Long.MAX_VALUE);
    assertEquals(status, service.createAndSubmit("o1", mapper.createObjectNode().set("data", state.deepCopy()), writer).path("approval").path("status").asText());
    verify(client, never()).definition(anyString());
    verifyNoInteractions(business, executor, jdbc);
  }
  @Test void duplicateOrderNumberFromAnotherRequestDoesNotReuseTheApproval() {
    var input = mapper.createObjectNode();
    var data = state.deepCopy();
    ((ObjectNode) data.path("orders").get(0)).put("approvalRequestId", "different-request");
    input.set("data", data);
    assertEquals(409, assertThrows(ApiException.class, () -> service.createAndSubmit("o1", input, writer)).status());
    verifyNoInteractions(business, executor, jdbc);
  }
  @Test void anotherInitiatorCannotClaimAnExistingSubmission() {
    record.put("openId", "ou_another_actor");
    var input = mapper.createObjectNode(); input.set("data", state.deepCopy());
    assertEquals(409, assertThrows(ApiException.class, () -> service.createAndSubmit("o1", input, writer)).status());
    verifyNoInteractions(business, executor, jdbc);
  }
  @Test void staleTerminalRetryCannotStartAnotherApproval() {
    record.put("status", "REJECTED");
    assertEquals(409, assertThrows(ApiException.class,
        () -> service.createAndSubmit("o1", mapper.createObjectNode(), writer)).status());
    verify(client, never()).definition(anyString());
  }
  @Test void permissionFailureInDefinitionLeavesOrderUnsaved() throws Exception {
    record = null;
    var input = mapper.createObjectNode(); input.set("data", state);
    when(client.definition("template")).thenThrow(new ApiException(503, "缺少飞书审批权限"));
    assertThrows(ApiException.class, () -> service.createAndSubmit("o1", input, writer));
    verifyNoInteractions(business, executor, jdbc);
  }
  @Test void configReportsMissingRemotePermissionAsUnavailable() {
    when(client.enabled()).thenReturn(true);
    when(client.enabled("销售订单")).thenReturn(true);
    when(client.definition("template")).thenThrow(new ApiException(503, "缺少飞书审批权限"));
    var config = service.config(writer);
    assertEquals(false, config.get("salesCanSubmit"));
    assertEquals("缺少飞书审批权限", config.get("salesMessage"));
  }
  @Test void missingDefinitionsNeverMakeOrdinaryApprovalOptional() {
    var config = service.config(writer);
    assertEquals(true, config.get("required"));
    assertEquals(false, config.get("salesEnabled"));
    assertEquals(false, config.get("prepaymentEnabled"));
    assertEquals(false, config.get("canSubmit"));
    verify(client, never()).definition(anyString());
  }
  @Test void configExposesRequiredRecipientAndDiscountFieldsFromTheActualSalesShape() throws Exception {
    when(client.enabled("销售订单")).thenReturn(true);
    when(client.definition("template")).thenReturn(FeishuDomesticApprovalTemplateTest.definition("sales"));
    var config = service.config(writer);
    assertEquals(true, config.get("salesReady"));
    assertEquals(true, config.get("salesRecipientRequired"));
    assertEquals(true, config.get("salesDiscountRequired"));
  }
  @Test void configRecognizesTheActualPrepaymentFormAndAllCategories() throws Exception {
    when(client.enabled("预收款订单")).thenReturn(true);
    when(client.definition("template")).thenReturn(FeishuDomesticApprovalTemplateTest.definition("prepayment"));
    var config = service.config(writer);
    assertEquals(true, config.get("prepaymentReceiptForm"));
    assertEquals(List.of("服务收入", "其他收入", "销售收入"), config.get("prepaymentReceiptCategories"));
  }
  @Test void flowResolvesUnboundApproversThroughTheDirectory() {
    var remote = instance("PENDING");
    remote.putArray("task_list").addObject().put("id", "task").put("node_id", "node").put("node_name", "负责人审批")
        .put("open_id", "ou_manager").put("status", "PENDING");
    when(client.instance("instance")).thenReturn(remote);
    when(client.userNames(anySet())).thenReturn(Map.of("ou_manager", "李经理"));
    var flow = service.flow("o1", reader);
    assertEquals("李经理", flow.path("nodes").get(0).path("tasks").get(0).path("name").asText());
    assertFalse(flow.toString().contains("ou_manager"));
  }
  @ParameterizedTest @ValueSource(strings={"销售订单", "海外提货单", "预收款订单"})
  void unsubmittedUnpaidOrdersCanBeCanceledWithoutAnyExternalWrite(String type) {
    record = null; ((ObjectNode) state.path("orders").get(0)).put("type", type).put("status", "草稿");
    var result = service.cancel("o1", writer);
    assertEquals("已取消", result.path("state").path("orders").get(0).path("status").asText());
    assertTrue(result.path("approval").isNull());
    verify(client, never()).cancel(any(), any(), any()); verify(client, never()).instance(any());
  }
  @Test void queuedCancellationStopsDispatchBeforeAnyApprovalExists() {
    record.put("status", "QUEUED").remove("instanceCode");
    assertEquals("已取消", service.cancel("o1", writer).path("state").path("orders").get(0).path("status").asText());
    assertEquals("CANCELED", record.path("status").asText());
    verify(client, never()).cancel(any(), any(), any());
  }
  @Test void pendingCancellationUsesTheOriginalInitiatorAndRequiresConfirmedWithdrawal() {
    when(client.instance("instance")).thenReturn(instance("PENDING"), instance("CANCELED"));
    var response = service.cancel("o1", writer);
    assertEquals("已取消", response.path("state").path("orders").get(0).path("status").asText());
    assertEquals("CANCELED", response.path("approval").path("status").asText());
    service.cancel("o1", writer);
    verify(client, times(1)).cancel("template", "instance", "ou_actor");
  }
  @Test void anotherInitiatorCannotWithdrawAPendingApproval() {
    record.put("openId", "ou_other");
    assertEquals(403, assertThrows(ApiException.class, () -> service.cancel("o1", writer)).status());
    verify(client, never()).cancel(any(), any(), any());
  }
  @Test void aLostWithdrawalResponseIsCompletedByLaterSynchronization() {
    when(client.instance("instance")).thenReturn(instance("PENDING"));
    doThrow(new ApiException(502, "撤回结果待确认")).when(client).cancel(any(), any(), any());
    assertThrows(ApiException.class, () -> service.cancel("o1", writer));
    assertEquals("sales", record.path("cancelRequestedBy").asText());
    assertEquals("审批中", state.path("orders").get(0).path("status").asText());
    when(client.instance("instance")).thenReturn(instance("CANCELED"));
    assertEquals("CANCELED", service.sync("o1", reader).path("status").asText());
    assertEquals("已取消", state.path("orders").get(0).path("status").asText());
    assertFalse(record.has("cancelRequestedBy"));
  }
  @Test void approvalPassingDuringWithdrawalIsAppliedButCannotBeCanceled() {
    when(client.instance("instance")).thenReturn(instance("APPROVED"));
    assertEquals(409, assertThrows(ApiException.class, () -> service.cancel("o1", writer)).status());
    assertEquals("已批准", state.path("orders").get(0).path("status").asText());
    verify(client, never()).cancel(any(), any(), any());
  }
  @Test void unknownSubmissionIsRecoveredBeforeWithdrawal() {
    record.put("status", "UNKNOWN").remove("instanceCode");
    when(client.instance("uuid")).thenReturn(instance("PENDING"));
    when(client.instance("instance")).thenReturn(instance("PENDING"), instance("CANCELED"));
    assertEquals("已取消", service.cancel("o1", writer).path("state").path("orders").get(0).path("status").asText());
    verify(client).instance("uuid"); verify(client).cancel("template", "instance", "ou_actor");
    verify(client, never()).create(any(), any(), any(), any());
  }
  @Test void anExistingFinancialJournalBlocksCancellationEvenWhenPaidIsZero() {
    when(db.rows("SELECT id FROM receipts WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.order'))=? LIMIT 1", "o1"))
        .thenReturn(List.of(Map.of("id", "refund-history")));
    assertThrows(ApiException.class, () -> service.cancel("o1", writer));
    verify(client, never()).cancel(any(), any(), any());
  }
  @Test void aNewExternalLinkDuringWithdrawalDoesNotCancelTheLinkedOrder() {
    when(client.instance("instance")).thenReturn(instance("PENDING"), instance("CANCELED"));
    doAnswer(call -> {
      when(db.rows(startsWith("SELECT order_id FROM jst_shipments"), eq("o1"), eq("o1"), eq("o1")))
          .thenReturn(List.of(Map.of("order_id", "o1")));
      return null;
    }).when(client).cancel(any(), any(), any());
    assertEquals(409, assertThrows(ApiException.class, () -> service.cancel("o1", writer)).status());
    assertEquals("草稿", state.path("orders").get(0).path("status").asText());
    assertEquals("CANCELED", record.path("status").asText());
    assertTrue(record.path("message").asText().contains("聚水潭关联"));
  }

  private ObjectNode instance(String status) {
    return mapper.createObjectNode().put("approval_code", "template").put("open_id", "ou_actor")
        .put("instance_code", "instance").put("uuid", "uuid").put("status", status);
  }
}
