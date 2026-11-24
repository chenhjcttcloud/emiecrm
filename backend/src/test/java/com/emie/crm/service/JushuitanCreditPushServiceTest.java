package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class JushuitanCreditPushServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final CrmRepository db = mock(CrmRepository.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  @SuppressWarnings("unchecked") final ObjectProvider<JushuitanCreditPushService.Transport> provider = mock(ObjectProvider.class);
  final JushuitanCreditPushService.Transport transport = mock(JushuitanCreditPushService.Transport.class);
  final Actor operator = new Actor("approver", "审批人", Set.of("business.read","business.write","order.approve"));
  final Actor reader = new Actor("reader", "只读", Set.of("business.read"));
  JushuitanCreditPushService service;
  ObjectNode approval, state, task;

  @BeforeEach void setup() throws Exception {
    var fixture = new JushuitanCreditPushPolicyTest();
    var order = fixture.order();
    approval = fixture.approval(order);
    JushuitanCreditPushPolicy.enqueue(approval,order);
    task=approval.path("creditPush").deepCopy();
    state=mapper.createObjectNode().put("revision",1);
    state.putArray("orders").add(order);
    state.set("products",fixture.products());
    when(db.jdbc()).thenReturn(jdbc);
    when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
    when(db.state(anyBoolean())).thenAnswer(call->state);
    when(db.object()).thenAnswer(call->mapper.createObjectNode());
    when(db.parse(anyString())).thenAnswer(call->mapper.readTree(call.getArgument(0,String.class)));
    when(db.encode(any())).thenAnswer(call->mapper.writeValueAsString(call.getArgument(0,Object.class)));
    when(db.rows(startsWith("SELECT payload FROM feishu_order_approvals WHERE order_id="),eq("o1")))
        .thenAnswer(call->List.of(Map.of("payload",approval.toString())));
    when(db.rows(startsWith("SELECT payload FROM jst_credit_pushes WHERE order_id="),eq("o1")))
        .thenAnswer(call->task==null?List.of():List.of(Map.of("payload",task.toString())));
    when(jdbc.update(startsWith("UPDATE jst_credit_pushes SET payload="),anyString(),eq("o1")))
        .thenAnswer(call->{task=(ObjectNode)mapper.readTree(call.getArgument(1,String.class));return 1;});
    when(jdbc.update(startsWith("INSERT INTO jst_credit_pushes(order_id,payload) VALUES"),eq("o1"),anyString()))
        .thenAnswer(call->{task=(ObjectNode)mapper.readTree(call.getArgument(2,String.class));return 1;});
    when(jdbc.update(startsWith("UPDATE feishu_order_approvals SET payload="),anyString(),eq("o1"),eq("instance")))
        .thenAnswer(call->{approval=(ObjectNode)mapper.readTree(call.getArgument(1,String.class));return 1;});
    when(provider.getIfAvailable()).thenReturn(transport);
    when(transport.unavailableMessage()).thenReturn("账期接口尚未启用");
    service=new JushuitanCreditPushService(db,manager,provider,"sandbox");
  }
  ObjectNode push(){return task;}
  ObjectNode response(JushuitanCreditPushService.Submission submission,String status){
    return mapper.createObjectNode().put("orderId",submission.orderId()).put("soId",submission.soId()).put("oId",123)
        .put("status",status).put("message","隔离接口结果").put("upstreamAttempted",true);
  }

  @Test void unavailableTransportLeavesTheTrueApprovalAndMoneyIntact() {
    service.process("o1");
    assertEquals("APPROVED",approval.path("status").asText());
    assertEquals("BLOCKED",push().path("status").asText());
    assertEquals(0,state.path("orders").get(0).path("paid").asInt());
    assertFalse(push().path("attempted").asBoolean());
    verify(transport,never()).push(any());verify(transport,never()).reconcile(any());verify(db,never()).receipt(any());
    verify(db,never()).state(any(JsonNode.class));
  }

  @Test void acceptedPushUsesOneFrozenIntentAndNeverBooksMoney() {
    when(transport.ready()).thenReturn(true);
    String so=push().path("soId").asText();
    when(transport.push(any())).thenAnswer(call->{
      var submission=call.getArgument(0,JushuitanCreditPushService.Submission.class);
      assertEquals(so,submission.soId());assertEquals(0,submission.orderSnapshot().path("paid").asInt());
      assertEquals("ON_ACCOUNT_TENDER",submission.input().path("accountingMode").asText());
      assertTrue(service.renew(submission));
      return response(submission,"ACCEPTED");
    });
    service.process("o1");service.process("o1");
    assertEquals("ACCEPTED",push().path("status").asText());assertEquals(123,push().path("oId").asInt());
    assertEquals(0,state.path("orders").get(0).path("paid").asInt());
    verify(transport,times(1)).push(any());verify(db,never()).receipt(any());verify(db,never()).state(any(JsonNode.class));
  }

  @Test void uncertainPushAndExplicitRetryOnlyReconcileTheSameOrder() {
    when(transport.ready()).thenReturn(true);
    when(transport.push(any())).thenThrow(new IllegalStateException("simulated timeout"));
    String so=push().path("soId").asText();
    service.process("o1");assertEquals("UNKNOWN",push().path("status").asText());
    service.retry("o1",operator);service.process("o1");
    assertEquals("UNKNOWN",push().path("status").asText());assertEquals(so,push().path("soId").asText());
    assertTrue(push().path("attempted").asBoolean());
    verify(transport,times(1)).push(any());verify(transport,times(1)).reconcile(any());
  }

  @Test void expiredLeaseDoesNotResendAndStaleWorkerCannotFinish() {
    when(transport.ready()).thenReturn(true);
    push().put("status","SUBMITTING").put("attemptId","expired").put("leaseUntil",0).put("attempted",true);
    when(transport.reconcile(any())).thenAnswer(call->response(call.getArgument(0),"ACCEPTED"));
    service.process("o1");assertEquals("ACCEPTED",push().path("status").asText());
    verify(transport,never()).push(any());verify(transport).reconcile(any());

    push().put("status","QUEUED").put("nextAttemptAt",0).put("attempted",false);
    AtomicReference<JushuitanCreditPushService.Submission> old=new AtomicReference<>();
    when(transport.push(any())).thenAnswer(call->{
      old.set(call.getArgument(0));push().put("attemptId","another-owner");
      return response(old.get(),"ACCEPTED");
    });
    service.process("o1");
    assertEquals("SUBMITTING",push().path("status").asText());assertEquals("another-owner",push().path("attemptId").asText());
    assertFalse(service.renew(old.get()));
  }

  @Test void localBlockDuringUnknownReconciliationCannotClearUncertaintyOrUnlockPayload() throws Exception {
    when(transport.ready()).thenReturn(true);
    push().put("status","UNKNOWN").put("attempted",true);
    when(transport.reconcile(any())).thenThrow(new JushuitanCreditPushService.Blocked("read API unavailable"));
    service.process("o1");
    assertEquals("UNKNOWN",push().path("status").asText());assertTrue(push().path("attempted").asBoolean());
    var before=push().deepCopy();
    var details=mapper.createObjectNode().put("creditFulfillment",true).put("accountingMode","ON_ACCOUNT_TENDER").put("shopId",99);
    service.prepare("o1",details,operator);
    assertEquals(before,push());verify(transport,never()).push(any());
  }

  @Test void wrongIdentityOrFractionalUpstreamIdIsNeverAccepted() {
    when(transport.ready()).thenReturn(true);
    when(transport.push(any())).thenAnswer(call->response(call.getArgument(0),"ACCEPTED").put("soId","wrong"));
    service.process("o1");assertEquals("UNKNOWN",push().path("status").asText());
    push().put("status","QUEUED").put("nextAttemptAt",0);
    doAnswer(call->response(call.getArgument(0),"ACCEPTED").put("oId",123.5)).when(transport).push(any());
    service.process("o1");assertEquals("UNKNOWN",push().path("status").asText());
  }

  @Test void historicalApprovalRequiresOneExplicitAuthorizedModeConfirmation() throws Exception {
    approval.remove(List.of("creditPush","creditPushPolicyVersion","creditPushProducts","creditPushEnvironment","creditPushAccountingMode"));
    task=null;
    ((ObjectNode)approval.path("orderSnapshot").path("approvalDraft")).remove("creditFulfillment");
    ((ObjectNode)state.path("orders").get(0).path("approvalDraft")).remove("creditFulfillment");
    service.process("o1");verify(transport,never()).push(any());
    var details=mapper.createObjectNode().put("creditFulfillment",true).put("accountingMode","ON_ACCOUNT_TENDER");
    assertThrows(ApiException.class,()->service.prepare("o1",details,reader));
    var invalid=details.deepCopy().put("accountingMode","NATIVE_CREDIT");
    assertThrows(ApiException.class,()->service.prepare("o1",invalid,operator));
    var result=service.prepare("o1",details,operator);String so=result.path("soId").asText();
    assertEquals("QUEUED",result.path("status").asText());
    assertEquals(so,service.prepare("o1",details,operator).path("soId").asText());
    verify(db,atLeastOnce()).audit(eq("approver"),contains("补齐账期推单资料"));
    verify(db,never()).state(any(JsonNode.class));
  }

  @Test void differingExistingJstIdentityBlocksAutomaticDuplicateCreation() {
    when(transport.ready()).thenReturn(true);
    when(db.rows("SELECT payload FROM jst_shipments WHERE order_id=?","o1"))
        .thenReturn(List.of(Map.of("payload","{\"soId\":\"CRM-existing-manual\"}")));
    service.process("o1");assertEquals("BLOCKED",push().path("status").asText());
    assertEquals(Long.MAX_VALUE,push().path("nextAttemptAt").asLong());verify(transport,never()).push(any());
  }

  @Test void explicitRejectionRequiresAnExplicitRetryAndKeepsTheSameIdentity() {
    when(transport.ready()).thenReturn(true);
    when(transport.push(any())).thenAnswer(call->response(call.getArgument(0),"FAILED").put("oId",0));
    String so=push().path("soId").asText();
    service.process("o1");service.process("o1");verify(transport,times(1)).push(any());
    assertEquals("FAILED",push().path("status").asText());
    service.retry("o1",operator);service.process("o1");verify(transport,times(2)).push(any());
    assertEquals(so,push().path("soId").asText());
  }

  @Test void finalWriteLeaseRechecksCurrentApprovalCoreAndEnvironmentButAllowsRealReceipts() throws Exception {
    for (String changed : List.of("status","total","environment","paid")) {
      setup(); when(transport.ready()).thenReturn(true);
      doAnswer(call -> {
        var submission=call.getArgument(0,JushuitanCreditPushService.Submission.class);
        var current=(ObjectNode)state.path("orders").get(0);
        switch(changed) {
          case "status" -> current.put("status","已取消");
          case "total" -> current.put("total",11);
          case "environment" -> push().put("environment","production");
          case "paid" -> current.put("paid",4);
          default -> throw new IllegalStateException();
        }
        assertEquals(changed.equals("paid"),service.renew(submission),changed);
        throw new JushuitanCreditPushService.Blocked("isolated preflight only");
      }).when(transport).push(any());
      service.process("o1");
    }
  }

  JushuitanCreditPushBootstrap.Authorization authorization() {
    return new JushuitanCreditPushBootstrap.Authorization("o1","uuid","instance","15","explicit-authorization-1",new java.math.BigDecimal("10"),java.math.BigDecimal.ZERO);
  }
  ObjectNode nativeApproved() {
    return mapper.createObjectNode().put("status","APPROVED").put("uuid","uuid").put("instance_code","instance")
        .put("approval_code","sales-template").put("open_id","native-initiator");
  }
  void historicalBootstrap() {
    task=null;
    approval.remove(List.of("creditPush","creditPushPolicyVersion","creditPushProducts","creditPushEnvironment","creditPushAccountingMode"));
    approval.put("approvalCode","sales-template").put("openId","native-initiator");
    ((ObjectNode)approval.path("orderSnapshot").path("approvalDraft")).remove(List.of("creditFulfillment","shopId","shopName"));
    ((ObjectNode)state.path("orders").get(0).path("approvalDraft")).remove(List.of("creditFulfillment","shopId","shopName"));
    when(db.rows("SELECT status,instance_code,payload FROM feishu_order_approvals WHERE order_id=? FOR UPDATE","o1"))
        .thenAnswer(call -> List.of(Map.of("status","APPROVED","instance_code","instance","payload",approval.toString())));
  }

  @Test void oneSpecificAuthorizedHistoricalOrderIsNativelyVerifiedAndAuditedOnceWithoutBookingPayment() {
    historicalBootstrap();
    var first=service.bootstrap(authorization(),nativeApproved());
    assertEquals("QUEUED",first.path("status").asText());assertEquals(15,push().path("input").path("shopId").asInt());
    assertEquals("EXPLICIT_USER_ONE_ORDER",push().path("authorizationSource").asText());
    assertEquals(0,state.path("orders").get(0).path("paid").asInt());
    ((ObjectNode)state.path("orders").get(0)).put("paid",3);
    assertEquals(first.path("soId"),service.bootstrap(authorization(),nativeApproved()).path("soId"));
    verify(db,times(1)).audit(eq("system:explicit-one-order-authorization"),contains("单次明确授权"),any(JsonNode.class));
    verify(db,never()).receipt(any());verify(db,never()).state(any(JsonNode.class));verify(transport,never()).push(any());
  }

  @Test void oneOrderAuthorizationRefusesNativeMismatchesAndChangedMoneyBeforeAnyIntentOrAudit() {
    for(String field:List.of("status","uuid","instance_code","approval_code","open_id")) {
      historicalBootstrap();
      var nativeResult=nativeApproved().put(field,"mismatch");
      assertThrows(ApiException.class,()->service.bootstrap(authorization(),nativeResult),field);
      assertFalse(approval.has("creditPush"));
    }
    ((ObjectNode)state.path("orders").get(0)).put("paid",1);
    assertThrows(ApiException.class,()->service.bootstrap(authorization(),nativeApproved()));
    assertFalse(approval.has("creditPush"));
    verify(db,never()).audit(anyString(),anyString(),any(JsonNode.class));
  }

  @Test void oneOrderAuthorizationCanRetainABlockedIntentButCannotDuplicateAnExistingShipment() {
    historicalBootstrap();
    ((ObjectNode)approval.path("orderSnapshot").path("approvalDraft")).remove("warehouseId");
    ((ObjectNode)state.path("orders").get(0).path("approvalDraft")).remove("warehouseId");
    assertEquals("BLOCKED",service.bootstrap(authorization(),nativeApproved()).path("status").asText());
    assertEquals("APPROVED",approval.path("status").asText());
    historicalBootstrap();
    when(db.rows("SELECT order_id FROM jst_shipments WHERE order_id=?","o1")).thenReturn(List.of(Map.of("order_id","o1")));
    assertThrows(ApiException.class,()->service.bootstrap(authorization(),nativeApproved()));
    assertFalse(approval.has("creditPush"));
  }

  JushuitanCreditPushBootstrap.Authorization shippingAuthorization() {
    var base=authorization();
    var shipping=mapper.createObjectNode().put("province","广东省").put("city","深圳市").put("district","龙岗区").put("address","万科星火online10栋");
    return new JushuitanCreditPushBootstrap.Authorization(base.orderId(),base.approvalUuid(),base.instanceCode(),base.shopId(),
        base.authorizationId(),base.expectedTotal(),base.expectedPaid(),"万科星火",shipping);
  }
  void shortOriginalAddress() {
    historicalBootstrap();
    ((ObjectNode)approval.path("orderSnapshot").path("approvalDraft")).put("recipientAddress","万科星火");
    ((ObjectNode)state.path("orders").get(0).path("approvalDraft")).put("recipientAddress","万科星火");
  }

  @Test void explicitlyAuthorizedAddressOnlyChangesShippingInputAndAuditsBeforeAfterWithoutPhone() {
    shortOriginalAddress();var orderBefore=state.path("orders").get(0).deepCopy();var snapshotBefore=approval.path("orderSnapshot").deepCopy();
    var first=service.bootstrap(shippingAuthorization(),nativeApproved());
    assertEquals("QUEUED",first.path("status").asText());
    assertEquals("广东省",push().path("input").path("recipient").path("province").asText());
    assertEquals("万科星火online10栋",push().path("input").path("recipient").path("address").asText());
    assertEquals(orderBefore,state.path("orders").get(0));assertEquals(snapshotBefore,approval.path("orderSnapshot"));
    assertEquals("万科星火",push().path("shippingAddressAuthorization").path("before").path("address").asText());
    assertEquals("龙岗区",push().path("shippingAddressAuthorization").path("after").path("district").asText());
    assertEquals(first.path("soId"),service.bootstrap(shippingAuthorization(),nativeApproved()).path("soId"));
    var detail=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db,times(1)).audit(eq("system:explicit-one-order-authorization"),anyString(),detail.capture());
    assertEquals("JST_CREDIT_ONE_ORDER_AUTHORIZED",detail.getValue().path("type").asText());
    assertFalse(detail.getValue().toString().contains("00000000000"));
    verify(db,never()).receipt(any());verify(db,never()).state(any(JsonNode.class));
    var generic=mapper.createObjectNode().put("creditFulfillment",true).put("accountingMode","ON_ACCOUNT_TENDER");
    generic.putObject("recipient").put("address","万科星火");
    assertThrows(ApiException.class,()->service.prepare("o1",generic,operator));
  }

  @Test void addressSupplementReusesAnUnattemptedBlockedIdentityButNeverChangesAnAttemptedRequest() {
    shortOriginalAddress();String so=service.bootstrap(authorization(),nativeApproved()).path("soId").asText();
    assertEquals("BLOCKED",push().path("status").asText());
    assertEquals(so,service.bootstrap(shippingAuthorization(),nativeApproved()).path("soId").asText());
    assertEquals("QUEUED",push().path("status").asText());
    assertEquals(so,service.bootstrap(shippingAuthorization(),nativeApproved()).path("soId").asText());
    shortOriginalAddress();service.bootstrap(authorization(),nativeApproved());push().put("attempted",true);
    var before=push().deepCopy();assertThrows(ApiException.class,()->service.bootstrap(shippingAuthorization(),nativeApproved()));assertEquals(before,push());
    shortOriginalAddress();service.bootstrap(authorization(),nativeApproved());
    when(db.rows("SELECT order_id FROM jst_shipments WHERE order_id=?","o1")).thenReturn(List.of(Map.of("order_id","o1")));
    assertThrows(ApiException.class,()->service.bootstrap(shippingAuthorization(),nativeApproved()));
  }

  @Test void addressAuthorizationCannotApplyToADifferentOriginalApprovedAddress() {
    historicalBootstrap();
    assertThrows(ApiException.class,()->service.bootstrap(shippingAuthorization(),nativeApproved()));assertFalse(approval.has("creditPush"));
    verify(db,never()).audit(anyString(),anyString(),any(JsonNode.class));
  }

  void parallelTask() {
    var current=(ObjectNode)state.path("orders").get(0);current.put("status","审批中");
    approval.put("status","QUEUED").put("instanceCode","");
    task=JushuitanCreditPushPolicy.intentForSubmission(approval,current,state.path("products"),"sandbox");
  }

  @Test void parallelSubmissionPushesWithNoNativeInstanceAndSurvivesFeishuFailureRejectionAndWithdrawal() throws Exception {
    for(String status:List.of("QUEUED","FAILED","UNKNOWN","REJECTED","CANCELED")) {
      setup();parallelTask();approval.put("status",status).put("uuid","later-approval-generation");
      if(!status.equals("QUEUED"))((ObjectNode)state.path("orders").get(0)).put("status","草稿");
      when(transport.ready()).thenReturn(true);
      doAnswer(call->{
        var submission=call.getArgument(0,JushuitanCreditPushService.Submission.class);
        assertEquals(2,submission.policyVersion());assertEquals("ORDER_SUBMISSION",submission.trigger());
        assertFalse(submission.submissionId().isBlank());assertEquals("",submission.instanceCode());
        assertEquals("审批中",submission.orderSnapshot().path("status").asText());assertTrue(service.renew(submission));
        return response(submission,"ACCEPTED");
      }).when(transport).push(any());
      service.process("o1");assertEquals("ACCEPTED",push().path("status").asText(),status);
      assertEquals(0,state.path("orders").get(0).path("paid").asInt());
    }
    verify(db,never()).receipt(any());verify(db,never()).state(any(JsonNode.class));
  }

  @Test void parallelTasksAreStableAcrossResubmissionsAndRejectAnyFrozenContentChange() {
    parallelTask();var original=task.deepCopy();var nextApproval=approval.deepCopy().put("uuid","new-uuid").put("instanceCode","new-instance");
    var current=(ObjectNode)state.path("orders").get(0);
    JushuitanCreditPushService.registerSubmission(db,nextApproval,current,state.path("products"),"sandbox");assertEquals(original,task);
    current.put("paid",2).put("note","后续备注");
    JushuitanCreditPushService.registerSubmission(db,nextApproval,current,state.path("products"),"sandbox");assertEquals(original,task);
    current.put("total",11);
    assertThrows(ApiException.class,()->JushuitanCreditPushService.registerSubmission(db,nextApproval,current,state.path("products"),"sandbox"));
    assertEquals(original,task);
    verify(jdbc,never()).update(startsWith("INSERT INTO jst_credit_pushes"),anyString(),anyString());
  }

  @Test void parallelTaskStopsAtTheFinalWriteBoundaryForCanceledDeletedOrChangedCoreOrders() throws Exception {
    for(String changed:List.of("cancelled","deleted","recipient")) {
      setup();parallelTask();when(transport.ready()).thenReturn(true);
      doAnswer(call->{
        var submission=call.getArgument(0,JushuitanCreditPushService.Submission.class);var current=(ObjectNode)state.path("orders").get(0);
        if(changed.equals("cancelled"))current.put("status","已取消");
        if(changed.equals("deleted"))current.put("deletedAt","2026-11-24T00:00:00Z");
        if(changed.equals("recipient"))((ObjectNode)current.path("approvalDraft")).put("recipientDistrict","福田区");
        assertFalse(service.renew(submission),changed);throw new JushuitanCreditPushService.Blocked("isolated no-write boundary");
      }).when(transport).push(any());service.process("o1");
    }
  }
}
