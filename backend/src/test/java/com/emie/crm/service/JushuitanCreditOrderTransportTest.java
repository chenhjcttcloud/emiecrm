package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JushuitanCreditOrderTransportTest {
  private static final String SO = "CRM-0123456789abcdef0123456789abcdef";
  private final ObjectMapper mapper = new ObjectMapper();
  private CrmRepository db;
  private JushuitanClient client;
  private JushuitanCreditPushService queue;
  private JushuitanService shipments;
  private PlatformTransactionManager manager;
  private JdbcTemplate jdbc;
  private JushuitanCreditOrderTransport transport;
  private String persisted;
  private boolean linked;

  private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
  private ObjectNode saved() throws Exception { return (ObjectNode)json(persisted); }
  private JushuitanCreditOrderTransport configured(String environment, String mode) {
    return new JushuitanCreditOrderTransport(db,client,mapper,manager,queue,shipments,environment,mode);
  }

  @BeforeEach void setup() throws Exception {
    db=mock(CrmRepository.class); client=mock(JushuitanClient.class); queue=mock(JushuitanCreditPushService.class);
    shipments=mock(JushuitanService.class); manager=mock(PlatformTransactionManager.class); jdbc=mock(JdbcTemplate.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(db.jdbc()).thenReturn(jdbc);
    when(db.state(true)).thenReturn(mapper.createObjectNode());
    when(db.parse(anyString())).thenAnswer(call->mapper.readTree((String)call.getArgument(0)));
    when(db.encode(any())).thenAnswer(call->mapper.writeValueAsString(call.getArgument(0)));
    when(db.rows(anyString(),eq("o1"))).thenAnswer(call->{
      String sql=call.getArgument(0);
      if(sql.contains("FROM jst_document_sales")) return linked?List.of(Map.of("order_id","o1")):List.of();
      return persisted==null?List.of():List.of(Map.of("payload",persisted));
    });
    when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call->{
      String sql=call.getArgument(0); Object[] args=(Object[])call.getRawArguments()[1];
      if(sql.startsWith("INSERT INTO jst_shipments")) persisted=(String)args[1];
      else if(sql.startsWith("UPDATE jst_shipments")) persisted=(String)args[0];
      else fail("Unexpected persistence: "+sql);
      return 1;
    });
    when(client.configured()).thenReturn(true); when(client.uploadsEnabled()).thenReturn(true);
    when(queue.renew(any())).thenReturn(true);
    doAnswer(call->{
      ObjectNode request=call.getArgument(0);
      ((ObjectNode)request.path("items").get(0)).put("i_id","STYLE-A").put("shop_i_id","STYLE-A");
      return null;
    }).when(shipments).validateCreditRequest(any());
    when(client.upload(any())).thenReturn(upload(true,"123",SO));
    when(client.query(eq("/open/orders/single/query"),any())).thenReturn(queried("WaitConfirm",123,SO));
    transport=configured("sandbox","ON_ACCOUNT_TENDER");
  }

  private JushuitanCreditPushService.Submission submission() throws Exception {
    return new JushuitanCreditPushService.Submission("o1",SO,"approval-1","instance-1","sandbox",json("""
        {"id":"o1","customerId":"c1","customer":"客户甲","type":"销售订单","status":"已批准","total":10,"paid":0,
         "items":[{"product":"p1","qty":1,"price":10}]}
        """),json("""
        [{"id":"p1","name":"产品甲","source":"jushuitan","enabled":true,"jstEnvironment":"sandbox","jstSku":"A"}]
        """),json("""
        {"accountingMode":"ON_ACCOUNT_TENDER","shopId":1,"warehouseId":15136229,"items":[{"skuId":"A"}],
         "recipient":{"name":"客户甲","phone":"13800000000","address":"广东省深圳市南山区测试路1号"}}
        """),"attempt-1");
  }

  private JsonNode upload(boolean success,String id,String so) throws Exception {
    return json("{\"code\":0,\"data\":{\"datas\":[{\"so_id\":\""+so+"\",\"o_id\":"+id+",\"issuccess\":"+success+"}]}}");
  }

  private JushuitanCreditPushService.Submission submitted(String approvalUuid,String instanceCode,String identity) throws Exception {
    var original=submission();var order=(ObjectNode)original.orderSnapshot().deepCopy();
    order.put("status","审批中");order.putObject("approvalDraft").put("creditFulfillment",true);
    return new JushuitanCreditPushService.Submission(original.orderId(),original.soId(),approvalUuid,instanceCode,
        original.environment(),order,original.products(),original.input(),original.attemptId(),2,"ORDER_SUBMISSION",identity);
  }

  @Test void formalSubmissionPushesBeforeFeishuInstanceExistsAndDoesNotBookCash() throws Exception {
    var submitted=submitted("initial-approval","","submission-stable");var before=submitted.orderSnapshot().deepCopy();
    assertEquals("ACCEPTED",transport.push(submitted).path("status").asText());
    assertEquals("审批中",saved().path("snapshot").path("status").asText());
    assertEquals("ORDER_SUBMISSION",saved().path("trigger").asText());
    assertEquals("submission-stable",saved().path("submissionId").asText());
    assertEquals(0,saved().path("snapshot").path("paid").asInt());
    assertTrue(saved().path("request").path("remark").asText().contains("财务须在聚水潭审核"));
    assertEquals(before,submitted.orderSnapshot());verify(db,never()).receipt(any());verify(db,never()).state(any(JsonNode.class));
  }

  @Test void feishuResubmissionKeepsTheOriginalErpIdentityAndCannotUploadTwice() throws Exception {
    transport.push(submitted("first-approval","","submission-stable"));
    assertEquals("ACCEPTED",transport.push(submitted("replacement-approval","new-instance","submission-stable")).path("status").asText());
    verify(client,times(1)).upload(any());
    assertThrows(JushuitanCreditPushService.Blocked.class,
        ()->transport.push(submitted("replacement-approval","new-instance","different-submission")));
    verify(client,times(1)).upload(any());
  }
  private JsonNode queried(String status,long id,String so) throws Exception {
    return json("{\"code\":0,\"data\":{\"orders\":[{\"shop_id\":1,\"so_id\":\""+so+"\",\"o_id\":"+id+",\"status\":\""+status+"\"}]}}");
  }

  @Test void explicitServerAccountingModeAndUploadCapabilityAreBothRequired() throws Exception {
    for(String mode:new String[]{"","CREDIT","on_account_tender"}) {
      var disabled=configured("sandbox",mode); assertFalse(disabled.ready());
      assertThrows(JushuitanCreditPushService.Blocked.class,()->disabled.push(submission()));
    }
    assertFalse(configured("unknown","ON_ACCOUNT_TENDER").ready());
    when(client.configured()).thenReturn(false); assertFalse(transport.ready());
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    when(client.configured()).thenReturn(true); when(client.uploadsEnabled()).thenReturn(false);
    assertFalse(transport.ready()); assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    assertNull(persisted); verify(client,never()).upload(any()); verify(queue,never()).renew(any());
  }

  @Test void rejectsWrongEnvironmentOrUnconfirmedInputBeforePersistence() throws Exception {
    var original=submission();
    var wrongMode=(ObjectNode)original.input().deepCopy(); wrongMode.remove("accountingMode");
    var invalidMode=new JushuitanCreditPushService.Submission(original.orderId(),SO,original.approvalUuid(),original.instanceCode(),
        "sandbox",original.orderSnapshot(),original.products(),wrongMode,original.attemptId());
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(invalidMode));
    var wrongEnvironment=new JushuitanCreditPushService.Submission(original.orderId(),SO,original.approvalUuid(),original.instanceCode(),
        "production",original.orderSnapshot(),original.products(),original.input(),original.attemptId());
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(wrongEnvironment));
    assertNull(persisted); verify(client,never()).upload(any());
  }

  @Test void persistsApprovalAndExactValidatedRequestBeforeRenewedUploadWithoutChangingCrmMoney() throws Exception {
    var submission=submission(); var orderBefore=submission.orderSnapshot().deepCopy(); var inputBefore=submission.input().deepCopy();
    when(client.upload(any())).thenAnswer(call->{
      var record=saved(); var actual=((JsonNode)call.getArgument(0)).get(0);
      assertTrue(record.path("upstreamAttempted").asBoolean());
      assertTrue(record.path("busy").asBoolean()); assertEquals("SUBMITTING",record.path("status").asText());
      assertEquals(actual,record.path("request")); assertEquals("STYLE-A",actual.path("items").get(0).path("i_id").asText());
      assertFalse(actual.has("receiver_state")); assertFalse(actual.has("receiver_city")); assertFalse(actual.has("receiver_district"));
      assertEquals(10,actual.path("pay").path("amount").asInt());
      assertEquals("账期挂账（非实收）",actual.path("pay").path("payment").asText());
      assertEquals(0,record.path("snapshot").path("paid").asInt()); assertFalse(record.has("effectiveOrder"));
      verify(queue).renew(submission);
      return upload(true,"123",SO);
    });
    var result=transport.push(submission);
    assertEquals("ACCEPTED",result.path("status").asText()); assertEquals(123,result.path("oId").asLong());
    var record=saved(); assertEquals(SO,record.path("soId").asText());
    assertEquals("approval-1",record.path("approvalUuid").asText()); assertEquals("instance-1",record.path("instanceCode").asText());
    assertEquals("sandbox",record.path("environment").asText()); assertTrue(record.path("creditFulfillment").asBoolean());
    assertEquals("ON_ACCOUNT_TENDER",record.path("accountingMode").asText()); assertFalse(record.path("busy").asBoolean());
    assertEquals(orderBefore,submission.orderSnapshot()); assertEquals(inputBefore,submission.input());
    verify(db,never()).state(any(JsonNode.class)); verify(db,never()).audit(anyString(),anyString());
    verify(db).audit(eq("SYSTEM"),eq("飞书审批自动账期推送：o1"),argThat(details->
        details.path("soId").asText().equals(SO) && details.path("approvalUuid").asText().equals("approval-1")
        && details.path("instanceCode").asText().equals("instance-1") && details.path("accountingMode").asText().equals("ON_ACCOUNT_TENDER")
        && details.path("noncash").asBoolean() && details.size()==5));
  }

  @Test void readOnlyPreflightFailureAndLostLeaseNeverUpload() throws Exception {
    doThrow(new ApiException("仓库不存在")).when(shipments).validateCreditRequest(any());
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    assertFalse(saved().path("upstreamAttempted").asBoolean()); assertFalse(saved().path("busy").asBoolean());
    verify(queue,never()).renew(any()); verify(client,never()).upload(any());
    doNothing().when(shipments).validateCreditRequest(any()); when(queue.renew(any())).thenReturn(false);
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    assertFalse(saved().path("upstreamAttempted").asBoolean()); verify(client,never()).upload(any());
  }

  @Test void refusesOtherShipmentIdentityOrExistingSalesDocument() throws Exception {
    linked=true;
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    assertNull(persisted); linked=false; transport.push(submission());
    var record=saved(); record.put("soId","CRM-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"); persisted=record.toString();
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    verify(client,times(1)).upload(any());
  }

  @Test void timeoutAndRepeatPushOnlyQueryFrozenOriginalIdentity() throws Exception {
    when(client.upload(any())).thenThrow(new ApiException("timeout"));
    assertEquals("UNKNOWN",transport.push(submission()).path("status").asText());
    var first=saved().path("request").deepCopy();
    var original=submission(); var changed=(ObjectNode)original.input().deepCopy(); changed.put("shopId",999).put("warehouseId",999);
    var retry=new JushuitanCreditPushService.Submission("o1",SO,"approval-1","instance-1","sandbox",
        original.orderSnapshot(),mapper.createArrayNode(),changed,"attempt-2");
    when(client.query(eq("/open/orders/single/query"),any())).thenAnswer(call->{
      JsonNode query=call.getArgument(1); assertEquals(1,query.path("shop_id").asLong());
      assertEquals(SO,query.path("so_ids").get(0).asText()); return queried("Sent",123,SO);
    });
    var result=transport.push(retry);
    assertEquals("SHIPPED",result.path("status").asText()); assertEquals("Sent",result.path("nativeStatus").asText());
    assertEquals(first,saved().path("request")); verify(client,times(1)).upload(any()); verify(queue,times(1)).renew(any());
  }

  @Test void uploadSuccessRequiresMatchingSoAndStrictPositiveIntegralOrderId() throws Exception {
    for(String id:new String[]{"0","-1","123.5","\"123\"","9223372036854775808","18446744073709551616"}) {
      persisted=null; when(client.upload(any())).thenReturn(upload(true,id,SO));
      assertEquals("UNKNOWN",transport.push(submission()).path("status").asText(),id);
    }
    persisted=null; when(client.upload(any())).thenReturn(upload(true,"123","OTHER"));
    assertEquals("UNKNOWN",transport.push(submission()).path("status").asText());
    persisted=null; when(client.upload(any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"so_id\":\""+SO+"\",\"o_id\":123,\"issuccess\":\"true\"}]}}"));
    assertEquals("UNKNOWN",transport.push(submission()).path("status").asText());
  }

  @Test void explicitRejectionIsFailedButAnotherPushStillCannotBlindlyResend() throws Exception {
    when(client.upload(any())).thenReturn(upload(false,"0",SO));
    var result=transport.push(submission()); assertEquals("FAILED",result.path("status").asText());
    assertTrue(result.path("upstreamAttempted").asBoolean());
    when(client.query(eq("/open/orders/single/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"orders\":[]}}"));
    assertEquals("UNKNOWN",transport.push(submission()).path("status").asText()); verify(client,times(1)).upload(any());
  }

  @Test void explicitNewAttemptCanResendConfirmedRejectionUsingIdenticalFrozenRequest() throws Exception {
    when(client.upload(any())).thenReturn(upload(false,"0",SO));
    transport.push(submission()); var frozen=saved().path("request").deepCopy();
    assertTrue(saved().path("confirmedRejected").asBoolean());
    var original=submission(); var changed=(ObjectNode)original.input().deepCopy(); changed.put("shopId",999);
    var retry=new JushuitanCreditPushService.Submission("o1",SO,"approval-1","instance-1","sandbox",
        original.orderSnapshot(),original.products(),changed,"explicit-retry-2");
    when(client.upload(any())).thenAnswer(call->{
      assertEquals(frozen,((JsonNode)call.getArgument(0)).get(0)); assertEquals(frozen,saved().path("request"));
      assertFalse(saved().path("confirmedRejected").asBoolean()); return upload(true,"123",SO);
    });
    assertEquals("ACCEPTED",transport.push(retry).path("status").asText());
    verify(client,times(2)).upload(any()); verify(client,never()).query(eq("/open/orders/single/query"),any());
    verify(queue).renew(retry);
    verify(db,times(1)).audit(eq("SYSTEM"),eq("飞书审批自动账期推送：o1"),any(JsonNode.class));
  }

  @Test void changedReadOnlyValidationBlocksRejectedRetryWithoutChangingFrozenPayload() throws Exception {
    when(client.upload(any())).thenReturn(upload(false,"0",SO)); transport.push(submission());
    var frozen=saved().path("request").deepCopy(); var original=submission();
    var retry=new JushuitanCreditPushService.Submission("o1",SO,"approval-1","instance-1","sandbox",
        original.orderSnapshot(),original.products(),original.input(),"explicit-retry-2");
    doAnswer(call->{
      ObjectNode request=call.getArgument(0); ((ObjectNode)request.path("items").get(0)).put("i_id","NEW-STYLE"); return null;
    }).when(shipments).validateCreditRequest(any());
    var failure=assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(retry));
    assertTrue(failure.getMessage().contains("不能修改冻结请求")); assertEquals(frozen,saved().path("request"));
    assertTrue(saved().path("confirmedRejected").asBoolean()); assertTrue(saved().path("upstreamAttempted").asBoolean());
    verify(client,times(1)).upload(any()); verify(queue,never()).renew(retry);
  }

  @Test void staleUploadCompletionCannotOverwriteNewerReconciledShipmentStatus() throws Exception {
    when(client.upload(any())).thenAnswer(call->{
      var newer=transport.reconcile(submission()); assertEquals("ACCEPTED",newer.path("status").asText());
      throw new ApiException("late timeout");
    });
    assertEquals("UNKNOWN",transport.push(submission()).path("status").asText());
    assertEquals("ACCEPTED",saved().path("status").asText()); assertEquals("WaitConfirm",saved().path("nativeStatus").asText());
    verify(client,times(1)).upload(any());
  }

  @Test void queryRetainsUncertaintyForMissingMismatchedDuplicateOrInvalidOrderAndNeverUploads() throws Exception {
    when(client.upload(any())).thenThrow(new ApiException("timeout")); transport.push(submission());
    for(JsonNode response:new JsonNode[]{json("{\"code\":0,\"data\":{\"orders\":[]}}"),queried("WaitConfirm",0,SO),
        queried("WaitConfirm",123,"OTHER"),json("{\"code\":500,\"data\":{\"orders\":[]}}"),
        json("{\"code\":0,\"data\":{\"orders\":[{\"so_id\":\""+SO+"\",\"o_id\":123},{\"so_id\":\""+SO+"\",\"o_id\":124}]}}")}) {
      when(client.query(eq("/open/orders/single/query"),any())).thenReturn(response);
      assertEquals("UNKNOWN",transport.reconcile(submission()).path("status").asText());
    }
    verify(client,times(1)).upload(any());
    persisted=null;
    assertEquals("UNKNOWN",transport.reconcile(submission()).path("status").asText()); assertNull(persisted);
  }

  @Test void exposesNativeWaitPayAndRejectsChangedUpstreamIdentity() throws Exception {
    transport.push(submission());
    when(client.query(eq("/open/orders/single/query"),any())).thenReturn(queried("WaitPay",123,SO));
    var result=transport.reconcile(submission()); assertEquals("ACCEPTED",result.path("status").asText());
    assertEquals("WaitPay",result.path("nativeStatus").asText()); assertTrue(result.path("message").asText().contains("仍待付款"));
    when(client.query(eq("/open/orders/single/query"),any())).thenReturn(queried("Sent",124,SO));
    assertEquals("UNKNOWN",transport.reconcile(submission()).path("status").asText());
    var wrongShop=(ObjectNode)queried("Sent",123,SO); ((ObjectNode)wrongShop.path("data").path("orders").get(0)).put("shop_id",2);
    when(client.query(eq("/open/orders/single/query"),any())).thenReturn(wrongShop);
    assertEquals("UNKNOWN",transport.reconcile(submission()).path("status").asText()); verify(client,times(1)).upload(any());
  }

  @Test void refusesAmountsThatWouldLosePrecisionInThePersistedFrozenRequest() throws Exception {
    var original=submission(); var order=(ObjectNode)original.orderSnapshot().deepCopy();
    order.put("total","9007199254740993.01"); ((ObjectNode)order.path("items").get(0)).put("price","9007199254740993.01");
    var invalid=new JushuitanCreditPushService.Submission("o1",SO,"approval-1","instance-1","sandbox",
        order,original.products(),original.input(),original.attemptId());
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(invalid));
    assertNull(persisted); verify(client,never()).upload(any());
  }

  @Test void logisticsChoiceIsFrozenForTheErpRequestAndUnknownResultsAreQueryOnly() throws Exception {
    var original = submitted("first-approval", "", "submission-stable");
    var order = (ObjectNode) original.orderSnapshot().deepCopy();
    order.withObject("approvalDraft").put("logisticsMode", "CARRIER").put("logisticsCode", "SF.5").put("logisticsCompany", "顺丰到付");
    var input = (ObjectNode) original.input().deepCopy();
    input.put("logisticsMode", "CARRIER").put("logisticsCode", "SF.5").put("logisticsCompany", "顺丰到付");
    var shipment = new JushuitanCreditPushService.Submission(original.orderId(),original.soId(),original.approvalUuid(),
        original.instanceCode(),original.environment(),order,original.products(),input,original.attemptId(),
        original.policyVersion(),original.trigger(),original.submissionId());
    when(client.upload(any())).thenAnswer(call -> {
      var payload = ((JsonNode) call.getArgument(0)).get(0);
      assertEquals("SF.5", payload.path("lc_id").asText());
      assertEquals("顺丰到付", payload.path("logistics_company").asText());
      assertEquals(payload, saved().path("request"));
      throw new ApiException("isolated timeout");
    });
    assertEquals("UNKNOWN", transport.push(shipment).path("status").asText());
    var frozen = saved().path("request").deepCopy();
    assertEquals("ACCEPTED", transport.reconcile(shipment).path("status").asText());
    assertEquals(frozen, saved().path("request"));
    verify(client,times(1)).upload(any());verify(db,never()).receipt(any());
  }

  @Test void aPreviouslySelectedCarrierCannotSilentlyBecomeWarehouseArrangedDuringRebuild() throws Exception {
    doThrow(new ApiException("isolated preflight failure")).when(shipments).validateCreditRequest(any());
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    var previous=saved();((ObjectNode)previous.path("request")).put("lc_id","SF.5").put("logistics_company","顺丰到付");
    persisted=previous.toString();
    assertThrows(JushuitanCreditPushService.Blocked.class,()->transport.push(submission()));
    assertEquals("SF.5",saved().path("request").path("lc_id").asText());verify(client,never()).upload(any());
  }
}
