package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class FeishuReceiptApprovalServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CrmRepository db = mock(CrmRepository.class);
  private final FeishuApprovalClient client = mock(FeishuApprovalClient.class);
  private final BusinessService business = mock(BusinessService.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Actor reader = new Actor("reader", "查看人", Set.of("business.read"));
  private final Actor submitter = new Actor("sales", "财务", Set.of("business.read", "business.write", "payment.write"));
  private final Actor salesSubmitter = new Actor("sales", "销售", Set.of("business.read", "business.write", "receipt.submit"));
  private final Actor other = new Actor("other", "财务", Set.of("business.read", "business.write", "payment.write"));
  private FeishuReceiptApprovalService service;
  private ObjectNode record;
  private ObjectNode state;

  @BeforeEach void prepare() throws Exception {
    when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    when(db.jdbc()).thenReturn(jdbc);
    when(db.object()).thenAnswer(call -> mapper.createObjectNode());
    when(db.parse(anyString())).thenAnswer(call -> mapper.readTree(call.getArgument(0, String.class)));
    when(db.encode(any())).thenAnswer(call -> mapper.writeValueAsString(call.getArgument(0, Object.class)));
    when(client.identityReady()).thenReturn(true);
    when(client.receiptEnabled()).thenReturn(true);
    when(client.receiptReady()).thenReturn(true);
    when(client.receiptApprovalCode()).thenReturn("receipt-code");
    when(client.appId()).thenReturn("app");
    when(client.definition("receipt-code")).thenReturn(definition());
    when(client.create(eq("receipt-code"), eq("ou_sales"), anyString(), any())).thenReturn("receipt-instance");
    when(db.rows(startsWith("SELECT open_id FROM feishu_identities"), eq("app"), anyString()))
        .thenAnswer(call -> List.of(Map.of("open_id", "sales".equals(call.getArgument(2)) ? "ou_sales" : "ou_other")));
    when(db.rows(startsWith("SELECT id FROM payment_methods"), eq("银行转账")))
        .thenReturn(List.of(Map.of("id", "bank")));
    when(db.rows(startsWith("SELECT payload FROM feishu_receipt_approvals WHERE id="), eq("batch-1")))
        .thenAnswer(call -> record == null ? List.of() : List.of(Map.of("payload", mapper.writeValueAsString(record))));
    when(jdbc.update(startsWith("INSERT INTO feishu_receipt_approvals"), eq("batch-1"), anyString(), nullable(String.class), anyString()))
        .thenAnswer(call -> {record = (ObjectNode) mapper.readTree(call.getArgument(4, String.class)); return 1;});
    when(jdbc.update(startsWith("UPDATE feishu_receipt_approvals SET status="), anyString(), nullable(String.class), anyString(), eq("batch-1")))
        .thenAnswer(call -> {record = (ObjectNode) mapper.readTree(call.getArgument(3, String.class)); return 1;});
    state = (ObjectNode) mapper.readTree("""
        {"revision":5,"customers":[{"id":"c1","name":"客户甲"}],
         "products":[{"id":"p1","name":"商品甲","jstSku":"SKU-1","model":"白色"},
                     {"id":"p2","name":"商品乙","code":"SKU-2","model":"蓝色"}],
         "orders":[
          {"id":"o1","type":"销售订单","status":"已批准","customerId":"c1","customer":"客户甲","owner":"签单人甲",
           "total":1000,"paid":0,"items":[{"product":"p1","qty":1,"price":1000}]},
          {"id":"o2","type":"预收款订单","status":"已批准","customerId":"c1","customer":"客户甲","owner":"签单人乙",
           "total":400,"paid":0,"items":[{"product":"p2","qty":2,"price":200}]}
         ]}
        """);
    when(db.state(true)).thenAnswer(call -> state);
    when(db.state(false)).thenAnswer(call -> state);
    when(business.state()).thenAnswer(call -> state);
    var configuredMethods = new BusinessService(db, mock(AssetService.class));
    when(business.requireEnabledPaymentMethod(any())).thenAnswer(call -> configuredMethods.requireEnabledPaymentMethod(call.getArgument(0)));
    service = new FeishuReceiptApprovalService(db, client, mapper, business, manager);
  }

  private ObjectNode input() throws Exception {
    return (ObjectNode) mapper.readTree("""
        {"requestId":"batch-1","revision":5,"customerId":"c1","amount":300,"method":"银行转账",
         "receiptDate":"2026-11-18","receiptCategory":"销售收入",
         "allocations":[{"orderId":"o1","amount":200},{"orderId":"o2","amount":100}]}
        """);
  }

  @ParameterizedTest @ValueSource(strings={"审批中","草稿","已取消"})
  void erpCreditReleaseDoesNotAuthorizeCashReceiptBeforeActualSalesApproval(String status) throws Exception {
    ((ObjectNode)state.path("orders").get(0)).put("status",status);
    when(db.rows("SELECT payload FROM jst_shipments WHERE order_id=?","o1"))
        .thenReturn(List.of(Map.of("payload","{\"creditFulfillment\":true,\"status\":\"SHIPPED\",\"oId\":123,\"effectiveOrder\":{\"total\":1000,\"paid\":1000}}")));
    assertThrows(ApiException.class,()->service.create(input(),submitter));
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
    verify(business,never()).applyApprovedReceipt(any());
    assertEquals(0,state.path("orders").get(0).path("paid").asInt());
  }

  private ObjectNode definition() throws Exception {
    return (ObjectNode) mapper.readTree("""
        {"form":[
          {"id":"project","name":"项目","type":"textarea","required":true},
          {"id":"customer","name":"客户","type":"input","required":true},
          {"id":"date","name":"收款发生日期","type":"date","required":true},
          {"id":"category","name":"收款类别","type":"radioV2","required":true,
           "option":[{"text":"销售收入","value":"income-sales"}]},
          {"id":"amount","name":"收款金额（元）","type":"amount","required":true,"option":{"currencyRange":["CNY"]}},
          {"id":"owner","name":"归属人","type":"contact","required":true},
          {"id":"note","name":"备注","type":"textarea","required":false}
        ],"node_list":[{"need_approver":false}]}
        """);
  }

  private void existing(String status) throws Exception {
    record = input().put("id", "batch-1").put("approvalCode", "receipt-code")
        .put("uuid", "original-uuid").put("openId", "ou_sales").put("status", status)
        .put("instanceCode", "receipt-instance").put("customer", "客户甲")
        .put("operator", "sales").put("operatorRole", "财务").put("booked", false);
    for (int index = 0; index < record.path("allocations").size(); index++) {
      var allocation = (ObjectNode) record.path("allocations").get(index);
      allocation.set("orderSnapshot", state.path("orders").get(index).deepCopy());
      allocation.set("executionSnapshot", state.path("orders").get(index).deepCopy());
      allocation.putArray("items");
    }
  }

  private ObjectNode instance(String status) {
    return mapper.createObjectNode().put("instance_code", "receipt-instance").put("approval_code", "receipt-code")
        .put("uuid", record.path("uuid").asText()).put("open_id", "ou_sales").put("status", status);
  }

  private JsonNode approval(JsonNode response) { return response.path("approval"); }

  @Test void failedActualReceiptVoucherRetryCannotUseChangedPrepaymentProposalRows() throws Exception {
    var prepayment = (ObjectNode) state.path("orders").get(1);
    var rows = prepayment.putArray("paymentDetails");
    rows.addObject().put("lineNo", 1).put("method", "银行转账").put("amount", 200).put("receiptDate", "2026-11-17");
    rows.addObject().put("lineNo", 2).put("method", "银行转账").put("amount", 200).put("receiptDate", "2026-11-18");
    existing("FAILED");
    record.putArray("form").addObject().put("id", "original-field").put("type", "textarea").put("value", "原收款申请");
    record.putObject(FeishuApprovalEvidence.FIELD).put("preparationFailed", true).put("createAttempted", false);
    var original = record.deepCopy();
    ((ObjectNode) rows.get(0)).put("receiptDate", "2026-11-16");
    assertTrue(assertThrows(ApiException.class, () -> service.retry("batch-1", submitter)).getMessage().contains("原收款订单"));
    assertEquals(original, record);
    verify(client, never()).uploadFile(anyString(), anyString(), anyString(), any(byte[].class));
    verify(client, never()).create(anyString(), anyString(), anyString(), any());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void configurationExposesOnlySalesIncomeEvenWhenTheTemplateOffersOtherCategories() throws Exception {
    var template=definition();
    var category=(ObjectNode)template.at("/form/3");
    ((ArrayNode)category.path("option")).addObject().put("text","服务收入").put("value","opaque-service");
    when(client.definition("receipt-code")).thenReturn(template);
    var config=service.config(submitter);
    assertTrue(config.path("canSubmit").asBoolean());
    assertEquals(mapper.createArrayNode().add("销售收入"),config.path("receiptCategories"));
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
  }

  @Test void configurationRejectsATemplateWithoutAnUnambiguousSalesIncomeOption() throws Exception {
    var template=definition();
    ((ObjectNode)template.at("/form/3/option/0")).put("text","服务收入").put("value","opaque-service");
    when(client.definition("receipt-code")).thenReturn(template);
    var config=service.config(submitter);
    assertFalse(config.path("canSubmit").asBoolean());
    assertTrue(config.path("message").asText().contains("销售收入"));
    assertTrue(config.path("receiptCategories").isEmpty());
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
  }

  @Test void oneBatchCapturesMultipleOrdersWithoutBookingPaymentsBeforeApproval() throws Exception {
    var result = service.create(input(), submitter);
    var published = approval(result);
    assertEquals("PENDING", published.path("status").asText());
    assertEquals(2, published.path("allocations").size());
    assertEquals(300, published.path("amount").asInt());
    assertFalse(published.path("booked").asBoolean());
    assertEquals("商品甲", record.path("allocations").get(0).path("items").get(0).path("name").asText());
    assertEquals("receipt-code", record.path("approvalCode").asText());
    assertFalse(record.path("uuid").asText().isBlank());
    assertEquals(0, state.path("orders").get(0).path("paid").asInt());
    assertEquals(0, state.path("orders").get(1).path("paid").asInt());
    assertFalse(published.has("form"));
    assertFalse(published.has("openId"));
    assertFalse(published.has("uuid"));
    assertFalse(published.path("allocations").get(0).has("orderSnapshot"));
    verify(client).create(eq("receipt-code"), eq("ou_sales"), anyString(), any(ArrayNode.class));
    verify(business, never()).applyApprovedReceipt(any());
    verify(db, never()).receipt(any());
  }

  private ObjectNode splitInput() throws Exception {
    var input=input();
    var details=input.putArray("paymentDetails");
    details.addObject().put("method","银行转账").put("amount",120).put("receiptDate","2026-10-31");
    details.addObject().put("method","自定义账户").put("amount",180).put("receiptDate","2026-11-02");
    when(db.rows(startsWith("SELECT id FROM payment_methods"),eq("自定义账户"))).thenReturn(List.of(Map.of("id","custom")));
    return input;
  }

  @Test void splitMethodsDatesAndOrderFundingAreFrozenBeforeTheOnlyNativeCreate() throws Exception {
    var input=splitInput();var original=input.deepCopy();
    var result=approval(service.create(input,submitter));
    ReceiptPaymentDetails.validateFrozen(record);
    assertEquals("组合收款",record.path("method").asText());
    assertEquals("2026-11-02",record.path("receiptDate").asText());
    assertEquals(2,result.path("paymentDetails").size());
    assertEquals(2,record.path("allocations").get(0).path("paymentDetails").size());
    assertEquals(80,record.path("allocations").get(0).path("paymentDetails").get(1).path("amount").asInt());
    assertEquals(100,record.path("allocations").get(1).path("paymentDetails").get(0).path("amount").asInt());
    assertEquals(original,input);
    var forms=org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
    verify(client,times(1)).create(eq("receipt-code"),eq("ou_sales"),anyString(),forms.capture());
    assertTrue(forms.getValue().toString().contains("2026-10-31"));
    assertTrue(forms.getValue().toString().contains("自定义账户"));
    verify(business,never()).applyApprovedReceipt(any());
    var repeated=input.deepCopy();repeated.withArray("paymentDetails").removeAll();
    assertEquals(result,approval(service.create(repeated,submitter)));
    verify(client,times(1)).create(eq("receipt-code"),eq("ou_sales"),anyString(),any());
  }

  @Test void newSplitReceiptRejectsInactiveMethodsAndAmountMismatchBeforeCreatingAnything() throws Exception {
    var input=splitInput();((ObjectNode)input.path("paymentDetails").get(0)).put("method","已停用账户");
    assertThrows(ApiException.class,()->service.create(input,submitter));
    assertNull(record);verify(client,never()).create(any(),any(),any(),any());
    var mismatched=splitInput();mismatched.put("amount",301);
    assertThrows(ApiException.class,()->service.create(mismatched,submitter));
    assertNull(record);verify(client,never()).create(any(),any(),any(),any());
  }

  @Test void mixedCreditCannotUsePrepaymentOrdersAndPendingMixedCreditReservesOnlyItsCreditPortion() throws Exception {
    var input=splitInput();((ObjectNode)input.path("paymentDetails").get(1)).put("method","预收款核销");
    ((ObjectNode)state.path("customers").get(0)).put("balance",300);
    assertThrows(ApiException.class,()->service.create(input,submitter));
    assertNull(record);
    ((ObjectNode)state.path("orders").get(1)).put("type","销售订单");
    var reserved=mapper.createObjectNode().put("customerId","c1").put("amount",200).put("method","组合收款");
    reserved.putArray("allocations").addObject().put("orderId","unrelated-order");
    var funding=reserved.putArray("paymentDetails");
    funding.addObject().put("method","银行转账").put("amount",160).put("receiptDate","2026-11-01");
    funding.addObject().put("method","预收款核销").put("amount",40).put("receiptDate","2026-11-01");
    when(db.rows(startsWith("SELECT payload FROM feishu_receipt_approvals WHERE status IN")))
        .thenReturn(List.of(Map.of("payload",reserved.toString())));
    ((ObjectNode)state.path("customers").get(0)).put("balance",200);
    assertThrows(ApiException.class,()->service.create(input,submitter));
    assertNull(record);verify(client,never()).create(any(),any(),any(),any());
    ((ObjectNode)state.path("customers").get(0)).put("balance",220);
    assertEquals("PENDING",approval(service.create(input,submitter)).path("status").asText());
  }

  private ObjectNode settlementSplitInput() throws Exception {
    var input=splitInput();
    ((ObjectNode)input.path("allocations").get(0)).put("amount",120);
    ((ObjectNode)input.path("allocations").get(1)).put("amount",180);
    var settlement=(ObjectNode)state.path("orders").get(1);
    settlement.put("type","销售订单").put("source","jushuitan-settlement");
    settlement.putObject("settlement").put("periodStart","2026-11-01");
    return input;
  }

  @Test void settlementDatesAreCheckedOnlyForTheFundingActuallyAssignedToThatOrder() throws Exception {
    var result=approval(service.create(settlementSplitInput(),submitter));
    assertEquals("PENDING",result.path("status").asText());
    assertEquals(1,record.path("allocations").get(1).path("paymentDetails").size());
    assertEquals(2,record.path("allocations").get(1).path("paymentDetails").get(0).path("lineNo").asInt());
  }

  @Test void aSettlementStillRejectsAnAssignedPaymentDatedBeforeItsOwnPeriod() throws Exception {
    var input=settlementSplitInput();
    ((ObjectNode)input.path("paymentDetails").get(1)).put("receiptDate","2026-10-30");
    assertTrue(assertThrows(ApiException.class,()->service.create(input,submitter)).getMessage().contains("到账日期"));
    assertNull(record);verify(client,never()).create(any(),any(),any(),any());
  }











  @Test void repeatedRequestIdReusesTheOriginalBatchEvenAfterFormOrStateChanges() throws Exception {
    var first = service.create(input(), submitter);
    var firstUuid = record.path("uuid").asText();
    state.put("revision", 9);
    var changed = input().put("amount", 999).put("method", "未启用方式");
    var repeated = service.create(changed, submitter);
    assertEquals(approval(first), approval(repeated));
    assertEquals(firstUuid, record.path("uuid").asText());
    verify(client, times(1)).create(eq("receipt-code"), eq("ou_sales"), anyString(), any());
    verify(client, times(1)).definition("receipt-code");
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void approvedReceiptsAreBookedExactlyOnceAcrossRepeatedSynchronization() throws Exception {
    existing("PENDING");
    when(client.instance("receipt-instance")).thenReturn(instance("APPROVED"));
    var first = approval(service.sync("batch-1", reader));
    assertEquals("APPROVED", first.path("status").asText());
    assertTrue(first.path("booked").asBoolean());
    assertFalse(first.path("bookedAt").asText().isBlank());
    assertTrue(approval(service.sync("batch-1", reader)).path("booked").asBoolean());
    verify(business, times(1)).applyApprovedReceipt(any());
    verify(client, times(1)).instance("receipt-instance");
    verify(client, never()).create(any(), any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"REJECTED", "CANCELED", "DELETED"})
  void terminatedApprovalsNeverBookAnyMoney(String status) throws Exception {
    existing("PENDING");
    when(client.instance("receipt-instance")).thenReturn(instance(status));
    assertEquals(status, approval(service.sync("batch-1", reader)).path("status").asText());
    assertFalse(record.path("booked").asBoolean());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"approval_code", "uuid", "open_id", "instance_code"})
  void aDifferentRemoteInstanceCannotChangeOrBookTheReceipt(String mismatchedField) throws Exception {
    existing("PENDING");
    when(client.instance("receipt-instance")).thenReturn(instance("APPROVED").put(mismatchedField, "different"));
    assertThrows(ApiException.class, () -> service.sync("batch-1", reader));
    assertEquals("PENDING", record.path("status").asText());
    verify(business, never()).applyApprovedReceipt(any());
    verifyNoInteractions(jdbc);
  }

  @Test void uncertainSubmissionCanOnlyRetryTheOriginalStoredUuidFormAndDefinition() throws Exception {
    existing("UNKNOWN");
    record.remove("instanceCode");
    record.put("receiptCategory","服务收入");
    record.putArray("form").addObject().put("id", "original-category").put("type","radioV2").put("value","legacy-opaque-service-value");
    var saved = (ArrayNode) record.path("form").deepCopy();
    when(client.instance("original-uuid")).thenThrow(mock(FeishuApprovalClient.NotFound.class));
    when(client.receiptApprovalCode()).thenReturn("changed-template");
    var result = approval(service.retry("batch-1", submitter));
    assertEquals("PENDING", result.path("status").asText());
    assertEquals("original-uuid", record.path("uuid").asText());
    assertEquals("receipt-code", record.path("approvalCode").asText());
    assertEquals("服务收入",record.path("receiptCategory").asText());
    assertEquals("服务收入",result.path("receiptCategory").asText());
    verify(client).create(eq("receipt-code"), eq("ou_sales"), eq("original-uuid"), eq(saved));
    verify(client, never()).definition(anyString());
    verify(business, never()).requireEnabledPaymentMethod(any());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void anUnknownSubmissionRecoveredByUuidDoesNotCreateAnotherApproval() throws Exception {
    existing("UNKNOWN");
    record.remove("instanceCode");
    record.putArray("form");
    when(client.instance("original-uuid")).thenReturn(instance("PENDING"));
    var result = approval(service.retry("batch-1", submitter));
    assertEquals("PENDING", result.path("status").asText());
    assertEquals("receipt-instance", result.path("instanceCode").asText());
    verify(client, never()).create(any(), any(), any(), any());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void rejectingAnOriginalUnknownRetryDoesNotReleaseTheUnresolvedReceiptReservation() throws Exception {
    existing("UNKNOWN");
    record.remove("instanceCode");
    record.putArray("form").addObject().put("id", "original-form");
    var originalForm = (ArrayNode) record.path("form").deepCopy();
    when(client.instance("original-uuid")).thenThrow(mock(FeishuApprovalClient.NotFound.class));
    when(client.create("receipt-code", "ou_sales", "original-uuid", originalForm))
        .thenThrow(mock(FeishuApprovalClient.Rejected.class));

    var retried = approval(service.retry("batch-1", submitter));

    assertEquals("UNKNOWN", retried.path("status").asText());
    assertEquals("UNKNOWN", record.path("status").asText());
    assertTrue(retried.path("canRetryOriginal").asBoolean());
    assertEquals("original-uuid", record.path("uuid").asText());
    assertEquals("receipt-code", record.path("approvalCode").asText());
    assertEquals(originalForm, record.path("form"));
    assertFalse(record.path("booked").asBoolean());
    assertFalse(record.has("instanceCode"));
    when(db.rows(startsWith("SELECT payload FROM feishu_receipt_approvals WHERE status IN")))
        .thenAnswer(call -> List.of(Map.of("payload", mapper.writeValueAsString(record))));
    var duplicatePayment = input().put("requestId", "another-batch");
    var duplicate = assertThrows(ApiException.class, () -> service.create(duplicatePayment, submitter));
    assertEquals(409, duplicate.status());
    assertTrue(duplicate.getMessage().contains("未完成的收款审批"));
    verify(client, times(1)).create(any(), any(), any(), any());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void unknownRecoveryRequiresBothTheOriginalUuidAndOpenId() throws Exception {
    existing("UNKNOWN");
    record.remove("instanceCode");
    var remote = instance("APPROVED"); remote.remove("open_id");
    when(client.instance("original-uuid")).thenReturn(remote);
    assertThrows(ApiException.class, () -> service.sync("batch-1", reader));
    assertEquals("UNKNOWN", record.path("status").asText());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void anotherInitiatorCannotReuseRetryOrCancelTheReceipt() throws Exception {
    existing("PENDING");
    when(client.instance("receipt-instance")).thenReturn(instance("PENDING"));
    assertEquals(403, assertThrows(ApiException.class, () -> service.create(input(), other)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.cancel("batch-1", other)).status());
    existing("UNKNOWN");
    when(client.instance("original-uuid")).thenThrow(mock(FeishuApprovalClient.NotFound.class));
    assertEquals(403, assertThrows(ApiException.class, () -> service.retry("batch-1", other)).status());
    verify(client, never()).create(any(), any(), any(), any());
    verify(client, never()).cancel(any(), any(), any());
  }

  @Test void businessReadWriteAndAReceiptPermissionAreRequiredBeforeSubmissionAndWrites() throws Exception {
    var input = input();
    for (var permissions : List.of(Set.<String>of(), Set.of("business.read"), Set.of("business.read", "business.write"),
        Set.of("business.read", "payment.write"), Set.of("receipt.submit"), Set.of("business.read", "receipt.submit"),
        Set.of("business.write", "receipt.submit"))) {
      var actor = new Actor("limited", "受限用户", permissions);
      assertEquals(403, assertThrows(ApiException.class, () -> service.create(input, actor)).status());
      assertEquals(403, assertThrows(ApiException.class, () -> service.retry("batch-1", actor)).status());
      assertEquals(403, assertThrows(ApiException.class, () -> service.cancel("batch-1", actor)).status());
    }
    var noRead = new Actor("visitor", "访客", Set.of());
    assertEquals(403, assertThrows(ApiException.class, () -> service.sync("batch-1", noRead)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.list(noRead)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.flow("batch-1", noRead)).status());
    verifyNoInteractions(jdbc);
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void failedBookingKeepsTheApprovedBatchAvailableForExactlyOneSuccessfulRetry() throws Exception {
    existing("APPROVED");
    when(business.applyApprovedReceipt(any())).thenThrow(new ApiException(409, "订单金额已变化"))
        .thenAnswer(call -> state);
    var failed = approval(service.sync("batch-1", reader));
    assertEquals("APPROVED", failed.path("status").asText());
    assertFalse(failed.path("booked").asBoolean());
    assertEquals("订单金额已变化", failed.path("bookingError").asText());
    var retried = approval(service.sync("batch-1", reader));
    assertTrue(retried.path("booked").asBoolean());
    assertFalse(retried.has("bookingError"));
    service.sync("batch-1", reader);
    verify(business, times(2)).applyApprovedReceipt(any());
    verify(client, never()).instance(anyString());
  }

  @Test void aStaleFormCannotSubmitOrBookNewPayments() throws Exception {
    var input = input().put("revision", 4);
    assertEquals(409, assertThrows(ApiException.class, () -> service.create(input, submitter)).status());
    verify(client, never()).create(any(), any(), any(), any());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void anOrderWithAnUnfinishedReceiptCannotBeReservedAgain() throws Exception {
    var reserved = mapper.createObjectNode().put("id", "another-batch").put("status", "PENDING");
    reserved.putArray("allocations").addObject().put("orderId", "o1");
    when(db.rows(startsWith("SELECT payload FROM feishu_receipt_approvals WHERE status IN")))
        .thenReturn(List.of(Map.of("payload", mapper.writeValueAsString(reserved))));
    assertEquals(409, assertThrows(ApiException.class, () -> service.create(input(), submitter)).status());
    verify(client, never()).create(any(), any(), any(), any());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void anUncertainOutboundResultKeepsTheSameFormAndUuidAvailableForRecovery() throws Exception {
    when(client.create(eq("receipt-code"), eq("ou_sales"), anyString(), any()))
        .thenThrow(mock(FeishuApprovalClient.Uncertain.class));
    var response = service.create(input(), submitter);
    assertEquals("UNKNOWN", approval(response).path("status").asText());
    assertTrue(approval(response).path("canRetryOriginal").asBoolean());
    assertTrue(record.path("form").isArray());
    var originalUuid = record.path("uuid").asText();
    service.create(input(), submitter);
    assertEquals(originalUuid, record.path("uuid").asText());
    verify(client, times(1)).create(eq("receipt-code"), eq("ou_sales"), anyString(), any());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void cancellationUsesTheOriginalDefinitionAndNeverBooksTheReceipt() throws Exception {
    existing("PENDING");
    when(client.receiptApprovalCode()).thenReturn("new-receipt-template");
    when(client.instance("receipt-instance")).thenReturn(instance("PENDING"), instance("PENDING"), instance("CANCELED"));
    var response = service.cancel("batch-1", submitter);
    assertEquals("CANCELED", approval(response).path("status").asText());
    assertFalse(record.has("cancelLeaseUntil"));
    assertFalse(record.has("cancelAttempt"));
    verify(client).cancel("receipt-code", "receipt-instance", "ou_sales");
    verify(business, never()).applyApprovedReceipt(any());
  }
  @Test void legacyReceiptSubmitPermissionWithoutPaymentWriteCannotStartAnApproval() throws Exception {
    assertThrows(ApiException.class, () -> service.create(input(), salesSubmitter));
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void matchingGuidFieldsIgnoreCaseWhileOperatorIdentityRemainsExact() throws Exception {
    existing("PENDING");
    when(client.instance("receipt-instance")).thenReturn(instance("REJECTED")
        .put("instance_code", "RECEIPT-INSTANCE").put("approval_code", "RECEIPT-CODE").put("uuid", "ORIGINAL-UUID"));
    assertEquals("REJECTED", approval(service.sync("batch-1", reader)).path("status").asText());
    verify(business, never()).applyApprovedReceipt(any());
  }

  @Test void publicReceiptAndFeishuProjectUseDisplayNameButPersistStableOperatorIdentity() throws Exception {
    when(db.rows("SELECT display_name FROM user_profiles WHERE name=?", "sales"))
        .thenReturn(List.of(Map.of("display_name", "显示姓名")));
    var response = approval(service.create(input(), submitter));
    assertEquals("显示姓名", response.path("operator").asText());
    assertEquals("sales", record.path("operator").asText());
    var form = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
    verify(client).create(eq("receipt-code"), eq("ou_sales"), anyString(), form.capture());
    assertTrue(form.getValue().get(0).path("value").asText().contains("登记人：显示姓名"));
  }

}
