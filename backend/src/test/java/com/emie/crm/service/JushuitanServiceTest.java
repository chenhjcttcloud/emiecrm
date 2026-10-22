package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JushuitanServiceTest {
 final ObjectMapper m=new ObjectMapper();
 CrmRepository db; JushuitanClient client; JushuitanService service; String persisted;
 final Actor admin=new Actor("admin","管理员",Set.of("business.write","order.approve"));
 JsonNode json(String s)throws Exception{return m.readTree(s);}
 @BeforeEach void setup()throws Exception{
  db=mock(CrmRepository.class);client=mock(JushuitanClient.class);
  var jdbc=mock(JdbcTemplate.class);when(db.jdbc()).thenReturn(jdbc);
  when(db.object()).thenAnswer(i->m.createObjectNode());
  when(db.parse(anyString())).thenAnswer(i->m.readTree((String)i.getArgument(0)));
  when(db.encode(any())).thenAnswer(i->m.writeValueAsString(i.getArgument(0)));
  when(db.state(true)).thenReturn(json("""
   {"products":[{"id":"p1","name":"甲"}],"orders":[{"id":"o1","customerId":"c1","type":"销售订单","status":"已批准","paid":10,"total":10,"items":[{"product":"p1","qty":1,"price":10}]}]}
   """));
  when(db.rows(anyString(),eq("o1"))).thenAnswer(i->persisted==null?List.of():List.of(Map.of("payload",persisted)));
  when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(i->{
   String sql=i.getArgument(0);Object[] args=(Object[])i.getRawArguments()[1];
   if(sql.startsWith("INSERT INTO jst_shipments"))persisted=(String)args[1];
   if(sql.startsWith("UPDATE jst_shipments"))persisted=(String)args[0];
   return 1;
  });
  var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  service=new JushuitanService(db,client,m,manager,"sandbox");
  when(client.configured()).thenReturn(true);
  when(client.query(eq("/open/sku/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"sku_id\":\"A\",\"i_id\":\"STYLE\",\"enabled\":1}]}}"));
  when(client.query(eq("/open/inventory/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"inventorys\":[{\"sku_id\":\"A\",\"qty\":5}]}}"));
  when(client.query(eq("/open/shops/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"shop_id\":1,\"shop_site\":\"商家自有商城\"}]}}"));
 }
 JsonNode input()throws Exception{return json("""
  {"shopId":1,"items":[{"skuId":"A"}],"recipient":{"name":"测试","phone":"00000000000","province":"广东省","city":"深圳市","district":"南山区","address":"测试勿投递"}}
  """);}
 @Test void persistsBeforeUploadAndPreventsRepeatedSubmission()throws Exception{
  when(client.upload(any())).thenAnswer(i->{
   assertNotNull(persisted);assertTrue(m.readTree(persisted).path("upstreamAttempted").asBoolean());
   String so=((JsonNode)i.getArgument(0)).get(0).path("so_id").asText();
   return json("{\"code\":0,\"data\":{\"datas\":[{\"issuccess\":true,\"o_id\":123,\"so_id\":\""+so+"\"}]}}");
  });
  var r=service.submit("o1",input(),admin);
  assertEquals("ACCEPTED",r.path("status").asText());assertEquals(123,r.path("oId").asInt());
  assertThrows(ApiException.class,()->service.submit("o1",input(),admin));
  verify(client,times(1)).upload(any());
 }
 @Test void timeoutIsUnknownAndCannotBeBlindlyRetried()throws Exception{
  when(client.upload(any())).thenThrow(new ApiException(502,"连接超时"));
  var r=service.submit("o1",input(),admin);
  assertEquals("UNKNOWN",r.path("status").asText());
  assertThrows(ApiException.class,()->service.submit("o1",input(),admin));
  verify(client,times(1)).upload(any());
 }
 @Test void stockOnlySkuCannotBeUploaded()throws Exception{
  when(client.query(eq("/open/sku/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[]}}"));
  assertEquals("FAILED",service.submit("o1",input(),admin).path("status").asText());
  verify(client,never()).upload(any());
 }
 @Test void writePermissionAloneCannotSubmit()throws Exception{
  assertThrows(ApiException.class,()->service.submit("o1",input(),new Actor("sales","销售",Set.of("business.write"))));
  verify(client,never()).upload(any());
 }
 @Test void transientSyncFailurePreservesAcceptedStatus()throws Exception{
  timeoutIsUnknownAndCannotBeBlindlyRetried();
  var record=(ObjectNode)m.readTree(persisted);record.put("status","ACCEPTED");persisted=record.toString();
  when(client.query(eq("/open/orders/single/query"),any())).thenThrow(new ApiException(502,"暂不可用"));
  var r=service.sync("o1");
  assertEquals("ACCEPTED",r.path("status").asText());assertTrue(r.has("syncError"));
 }
 void acceptedOrder() throws Exception {
  when(client.upload(any())).thenAnswer(i->{
   String so=((JsonNode)i.getArgument(0)).get(0).path("so_id").asText();
   return m.createObjectNode().put("code",0).set("data",m.createObjectNode().set("datas",m.createArrayNode().add(m.createObjectNode().put("so_id",so).put("o_id",123).put("issuccess",true))));
  });
  service.submit("o1",input(),admin);
  when(client.changesEnabled()).thenReturn(true);
  when(client.query(eq("/open/orders/out/simple/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[]}}"));
  upstreamOrder("WaitConfirm", null);
 }
 void upstreamOrder(String status, JsonNode target) {
  var order=m.createObjectNode().put("o_id",123).put("status",status).put("pay_amount",10);
  try { order.set("items",m.readTree(persisted).path("request").path("items").deepCopy()); } catch(Exception e) { throw new RuntimeException(e); }
  if(target!=null)order.setAll((ObjectNode)target);
  when(client.query(eq("/open/orders/single/query"),any())).thenReturn(m.createObjectNode().put("code",0).set("data",m.createObjectNode().set("orders",m.createArrayNode().add(order))));
 }
 ObjectNode changeInput(String kind) throws Exception {
  var body=(ObjectNode)input();
  body.put("kind",kind).put("reason","客户申请修改").put("version",m.readTree(persisted).path("editVersion").asText(m.readTree(persisted).path("updatedAt").asText()));
  body.put("remark","新的发货备注");return body;
 }
 @Test void addressOnlyConfirmedAfterReadbackAndPreservesOriginal() throws Exception {
  acceptedOrder(); var before=m.readTree(persisted).path("request").deepCopy();
  var body=changeInput("ADDRESS");((ObjectNode)body.path("recipient")).put("address","新测试地址勿投递");
  when(client.mutate(anyString(),anyString(),any())).thenAnswer(i->{
   assertEquals("PENDING",m.readTree(persisted).path("change").path("status").asText());
   var target=((ObjectNode)i.getArgument(2)).deepCopy();target.remove(List.of("shop_id","o_id"));
   upstreamOrder("WaitConfirm",target);
   return json("{\"code\":0,\"data\":{\"issuccess\":true,\"data\":true}}");
  });
  var result=service.change("o1",body,admin);
  assertEquals("CONFIRMED",result.path("change").path("status").asText());
  assertEquals("新测试地址勿投递",result.path("request").path("receiver_address").asText());
  assertEquals(before,result.path("changes").get(0).path("before"));
  assertEquals(before.path("items"),result.path("request").path("items"));
 }
 @Test void timeoutBlocksFurtherChangesAndCanReconcileLater() throws Exception {
  acceptedOrder();doThrow(new ApiException("超时")).when(client).mutate(anyString(),anyString(),any());
  var result=service.change("o1",changeInput("REMARK"),admin);
  assertEquals("UNKNOWN",result.path("change").path("status").asText());
  assertThrows(ApiException.class,()->service.change("o1",changeInput("REMARK"),admin));
  verify(client,times(1)).mutate(anyString(),anyString(),any());
  upstreamOrder("WaitConfirm",result.path("change").path("target"));
  assertEquals("CONFIRMED",service.sync("o1").path("change").path("status").asText());
 }
 @Test void acknowledgementWithoutMatchingReadbackIsNotSuccess() throws Exception {
  acceptedOrder();when(client.mutate(anyString(),anyString(),any())).thenReturn(json("{\"code\":0,\"issuccess\":true}"));
  var result=service.change("o1",changeInput("REMARK"),admin);
  assertEquals("PENDING",result.path("change").path("status").asText());
  assertFalse(result.has("changes"));
 }
 @Test void rejectionIsRecordedWithoutChangingSnapshot() throws Exception {
  acceptedOrder();var before=m.readTree(persisted).path("request");
  when(client.mutate(anyString(),anyString(),any())).thenReturn(json("{\"code\":0,\"issuccess\":false,\"msg\":\"无权限\"}"));
  var result=service.change("o1",changeInput("REMARK"),admin);
  assertEquals("REJECTED",result.path("change").path("status").asText());
  assertEquals(before,result.path("request"));assertEquals(1,result.path("changes").size());
 }
 @Test void shippedOrOutboundOrdersCannotBeChanged() throws Exception {
  acceptedOrder();upstreamOrder("Sent",null);
  assertThrows(ApiException.class,()->service.change("o1",changeInput("ADDRESS"),admin));
  upstreamOrder("WaitConfirm",null);
  when(client.query(eq("/open/orders/out/simple/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"io_id\":1}]}}"));
  assertThrows(ApiException.class,()->service.change("o1",changeInput("CANCEL"),admin));
  verify(client,never()).mutate(anyString(),anyString(),any());
 }
 @Test void cancellationRequiresReadbackAndDoesNotModifyCrmMoney() throws Exception {
  acceptedOrder();when(client.mutate(anyString(),anyString(),any())).thenAnswer(i->{upstreamOrder("Cancelled",null);return json("{\"code\":0}");});
  var result=service.change("o1",changeInput("CANCEL"),admin);
  assertEquals("CONFIRMED",result.path("change").path("status").asText());
  assertEquals("CANCELLED",result.path("status").asText());
  verify(db,never()).state(any(JsonNode.class));
 }
 @Test void staleVersionAndMissingPermissionCannotMutate() throws Exception {
  acceptedOrder();var body=changeInput("ADDRESS");body.put("version","old");
  assertThrows(ApiException.class,()->service.change("o1",body,admin));
  assertThrows(ApiException.class,()->service.change("o1",changeInput("ADDRESS"),new Actor("sales","销售",Set.of("business.write"))));
  when(client.changesEnabled()).thenReturn(false);
  assertThrows(ApiException.class,()->service.change("o1",changeInput("ADDRESS"),admin));
  verify(client,never()).mutate(anyString(),anyString(),any());
 }

 final Actor finance=new Actor("finance","财务",Set.of("business.write","order.approve","payment.write"));
 boolean oldCancelled; JsonNode newRequest;
 void amendmentSetup() throws Exception {
  acceptedOrder();oldCancelled=false;newRequest=null;
  when(db.state(false)).thenAnswer(i->db.state(true));
  when(db.rows(contains("payment_methods"),eq("银行转账"))).thenReturn(List.of(Map.of("id","bank")));
  when(client.mutate(anyString(),eq("/open/jushuitan/orderbyoid/cancel"),any())).thenAnswer(i->{oldCancelled=true;return json("{\"code\":0}");});
  when(client.query(eq("/open/orders/single/query"),any())).thenAnswer(i->{
   var params=(JsonNode)i.getArgument(1);String so=params.path("so_ids").get(0).asText();
   var orders=m.createArrayNode();
   if(newRequest!=null&&newRequest.path("so_id").asText().equals(so)) {
    var actual=((ObjectNode)newRequest).deepCopy();actual.put("o_id",456).put("status","WaitConfirm");orders.add(actual);
   } else if(so.equals(m.readTree(persisted).path("amendment").path("oldSoId").asText())) {
    var old=m.createObjectNode().put("o_id",123).put("so_id",so).put("pay_amount",10).put("status",oldCancelled?"Cancelled":"WaitConfirm");
    old.set("items",m.readTree(persisted).path("request").path("items"));orders.add(old);
   }
   return m.createObjectNode().put("code",0).set("data",m.createObjectNode().set("orders",orders));
  });
  doAnswer(i->{
   newRequest=((JsonNode)i.getArgument(0)).get(0).deepCopy();
   return m.createObjectNode().put("code",0).set("data",m.createObjectNode().set("datas",m.createArrayNode().add(m.createObjectNode().put("so_id",newRequest.path("so_id").asText()).put("o_id",456).put("issuccess",true))));
  }).when(client).upload(any());
 }
 ObjectNode amendmentInput(int price) throws Exception {
  var body=m.createObjectNode().put("version",m.readTree(persisted).path("editVersion").asText(m.readTree(persisted).path("updatedAt").asText())).put("reason","客户调整商品");
  body.putArray("items").addObject().put("product","p1").put("qty",1).put("price",price);
  body.putArray("mappings").addObject().put("skuId","A");return body;
 }
 ObjectNode settlement(int amount) {
  return m.createObjectNode().put("amount",amount).put("method","银行转账").put("reference","BANK-123");
 }
 @Test void amendmentCancelsOldBeforeCollectingDifferenceAndPreservesOriginal() throws Exception {
  amendmentSetup();var result=service.amend("o1",amendmentInput(20),admin);
  assertTrue(oldCancelled);assertEquals("AWAITING_FUNDS",result.path("amendment").path("status").asText());
  verify(client,times(1)).upload(any()); // Only the original submit, no unfunded replacement.
  assertEquals(10,m.readTree(persisted).path("snapshot").path("total").asInt());
  assertThrows(ApiException.class,()->service.continueAmendment("o1",settlement(9),finance));
  assertThrows(ApiException.class,()->service.continueAmendment("o1",settlement(10),admin));
  verify(db,never()).receipt(any());
  result=service.continueAmendment("o1",settlement(10),finance);
  assertEquals("COMPLETE",result.path("amendment").path("status").asText());
  assertEquals(20,result.path("effectiveOrder").path("total").asInt());assertEquals(456,result.path("oId").asInt());
  var captor=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(db).receipt(captor.capture());
  assertEquals(10,captor.getValue().path("amount").asInt());assertEquals("订单变更",captor.getValue().path("type").asText());
  assertThrows(ApiException.class,()->service.continueAmendment("o1",settlement(10),finance));
  verify(client,times(2)).upload(any());verify(db,never()).state(any(JsonNode.class));
 }
 @Test void refundIsSignedLedgerEntryAndDoesNotRewriteOriginalReceipt() throws Exception {
  amendmentSetup();service.amend("o1",amendmentInput(5),admin);
  var result=service.continueAmendment("o1",settlement(5),finance);
  assertEquals("COMPLETE",result.path("amendment").path("status").asText());
  var captor=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(db).receipt(captor.capture());
  assertEquals(-5,captor.getValue().path("amount").asInt());
  assertEquals(10,m.readTree(persisted).path("snapshot").path("paid").asInt());
 }
 @Test void equalAmountReplacementNeedsNoAdditionalCashEntry() throws Exception {
  amendmentSetup();service.amend("o1",amendmentInput(10),admin);
  assertEquals("READY",m.readTree(persisted).path("amendment").path("status").asText());
  assertEquals("COMPLETE",service.continueAmendment("o1",m.createObjectNode(),admin).path("amendment").path("status").asText());
  verify(db,never()).receipt(any());
 }
 @Test void uncertainOldCancellationBlocksNewOrderAndAnotherAmendment() throws Exception {
  amendmentSetup();doThrow(new ApiException("超时")).when(client).mutate(anyString(),anyString(),any());
  var result=service.amend("o1",amendmentInput(20),admin);
  assertEquals("WAIT_CANCEL",result.path("amendment").path("status").asText());
  service.continueAmendment("o1",settlement(10),finance);
  assertThrows(ApiException.class,()->service.amend("o1",amendmentInput(30),admin));
  verify(client,times(1)).upload(any());verify(db,never()).receipt(any());
 }
 @Test void replacementRetryUsesSameIdentityAndSettlementReceipt() throws Exception {
  amendmentSetup();service.amend("o1",amendmentInput(20),admin);
  doReturn(json("{\"code\":0,\"data\":{\"datas\":[]}}")).when(client).upload(any());
  var result=service.continueAmendment("o1",settlement(10),finance);
  assertEquals("UNKNOWN",result.path("amendment").path("status").asText());
  assertThrows(ApiException.class,()->service.continueAmendment("o1",settlement(10),finance));
  var stored=(ObjectNode)m.readTree(persisted);((ObjectNode)stored.path("amendment")).put("startedAt",1);persisted=stored.toString();
  service.continueAmendment("o1",m.createObjectNode(),admin);
  var uploads=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(client,times(3)).upload(uploads.capture());
  assertEquals(uploads.getAllValues().get(1),uploads.getAllValues().get(2));
  var receipts=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(db,times(2)).receipt(receipts.capture());
  assertEquals(receipts.getAllValues().get(0).path("id"),receipts.getAllValues().get(1).path("id"));
 }

 @Test void externalSkuMismatchStopsCancellationBeforeAnyWrite() throws Exception {
  acceptedOrder();upstreamOrder("WaitConfirm",json("{\"items\":[{\"sku_id\":\"OTHER\",\"qty\":1}]}"));
  assertThrows(ApiException.class,()->service.change("o1",changeInput("CANCEL"),admin));
  verify(client,never()).mutate(anyString(),anyString(),any());
 }
 @Test void replacementMismatchBlocksUnknownRetryEvenAfterCooldown() throws Exception {
  amendmentSetup();service.amend("o1",amendmentInput(20),admin);
  doAnswer(i->{newRequest=((JsonNode)i.getArgument(0)).get(0).deepCopy();((ObjectNode)newRequest).put("pay_amount",999);return json("{\"code\":0}");}).when(client).upload(any());
  var result=service.continueAmendment("o1",settlement(10),finance);
  assertEquals("UNKNOWN",result.path("amendment").path("status").asText());assertTrue(result.path("amendment").path("conflict").asBoolean());
  var saved=(ObjectNode)m.readTree(persisted);((ObjectNode)saved.path("amendment")).put("startedAt",1);persisted=saved.toString();
  assertThrows(ApiException.class,()->service.continueAmendment("o1",m.createObjectNode(),admin));
  verify(client,times(2)).upload(any());
 }

 @Test void unchangedSyncPreservesEditVersionButBusinessChangeInvalidatesIt() throws Exception {
  acceptedOrder();var version=m.readTree(persisted).path("editVersion").asText();
  service.sync("o1");assertEquals(version,m.readTree(persisted).path("editVersion").asText());
  upstreamOrder("Sent",null);service.sync("o1");assertNotEquals(version,m.readTree(persisted).path("editVersion").asText());
 }
 @Test void confirmedCancellationRecoversDraftAfterCrashAndCannotBeAbandoned() throws Exception {
  amendmentSetup();service.amend("o1",amendmentInput(20),admin);
  var record=(ObjectNode)m.readTree(persisted);((ObjectNode)record.path("amendment")).put("status","DRAFT");persisted=record.toString();
  assertThrows(ApiException.class,()->service.continueAmendment("o1",m.createObjectNode().put("abandon",true),admin));
  var result=service.continueAmendment("o1",settlement(10),finance);
  assertEquals("COMPLETE",result.path("amendment").path("status").asText());
  verify(client,times(1)).mutate(anyString(),anyString(),any());
 }
 @Test void fullLengthAmendmentReasonDoesNotOverflowCancellationReason() throws Exception {
  amendmentSetup();var input=amendmentInput(20);input.put("reason","改".repeat(160));
  assertEquals("AWAITING_FUNDS",service.amend("o1",input,admin).path("amendment").path("status").asText());
 }
 @Test void pollFiltersEnvironmentBeforeLimit() {
  when(db.rows(contains("WHERE JSON_UNQUOTE"),eq("sandbox"))).thenReturn(List.of());
  service.poll();verify(db).rows("SELECT order_id,payload FROM jst_shipments WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.environment'))=? ORDER BY updated_at LIMIT 10","sandbox");
 }

}
