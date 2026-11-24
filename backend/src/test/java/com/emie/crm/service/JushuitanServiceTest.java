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
   {"products":[{"id":"p1","name":"甲","source":"jushuitan","enabled":true,"jstEnvironment":"sandbox","jstSku":"A"}],"orders":[{"id":"o1","customerId":"c1","type":"销售订单","status":"已批准","paid":10,"total":10,"items":[{"product":"p1","qty":1,"price":10}]}]}
   """));
  when(db.state(false)).thenAnswer(i->db.state(true));
  when(db.rows(anyString(),eq("o1"))).thenAnswer(i->i.getArgument(0,String.class).contains("FROM jst_credit_pushes")
      || persisted==null?List.of():List.of(Map.of("payload",persisted)));
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
  when(client.uploadsEnabled()).thenReturn(true);
  when(client.query(eq("/open/wms/partner/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"wms_co_id\":10048859,\"is_main\":true,\"name\":\"主仓\",\"status\":\"生效\"}]}}"));
  when(client.query(eq("/open/sku/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"sku_id\":\"A\",\"i_id\":\"STYLE\",\"enabled\":1}]}}"));
  when(client.query(eq("/open/inventory/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"inventorys\":[{\"sku_id\":\"A\",\"qty\":5}]}}"));
  when(client.query(eq("/open/shops/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"shop_id\":1,\"shop_site\":\"商家自有商城\"}]}}"));
 }
 JsonNode input()throws Exception{return json("""
  {"shopId":1,"warehouseId":10048859,"items":[{"skuId":"A"}],"recipient":{"name":"测试","phone":"00000000000","province":"广东省","city":"深圳市","district":"南山区","address":"测试勿投递"}}
  """);}
 @Test void readOnlyConnectionDoesNotPersistOrAttemptOrderUpload() throws Exception {
  when(client.uploadsEnabled()).thenReturn(false);
  assertThrows(ApiException.class,()->service.submit("o1",input(),admin));
  assertNull(persisted);verify(client,never()).upload(any());
 }
 @Test void inventoryIsScopedToExplicitValidWarehouse() throws Exception {
  assertThrows(ApiException.class,()->service.inventory("A",0));
  assertThrows(ApiException.class,()->service.inventory("A",999));
  service.inventory("A",10048859);
  verify(client).query(eq("/open/inventory/query"),argThat(b-> b instanceof Map<?,?> values
      && Long.valueOf(10048859).equals(values.get("wms_co_id")) && Boolean.TRUE.equals(values.get("has_lock_qty"))));
 }
 @Test void invalidWarehouseNeverUploads() throws Exception {
  var request=(ObjectNode)input();request.put("warehouseId",999);
  assertEquals("FAILED",service.submit("o1",request,admin).path("status").asText());
  verify(client,never()).upload(any());
 }
 @Test void explicitlyPlannedCarrierMustBeConfiguredInTheSelectedWarehouseBeforeUpload() throws Exception {
  var state=(ObjectNode)db.state(true);var order=(ObjectNode)state.path("orders").get(0);
  order.putObject("approvalDraft").put("logisticsMode","CARRIER").put("logisticsCode","SF.5").put("logisticsCompany","顺丰到付");
  var request=(ObjectNode)input();request.put("logisticsMode","CARRIER").put("logisticsCode","SF.5").put("logisticsCompany","顺丰到付");
  when(client.query(eq("/open/logisticscompany/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"lc_id\":\"SF\",\"lc_name\":\"顺丰速运\"}],\"has_next\":false}}"));
  assertEquals("FAILED",service.submit("o1",request,admin).path("status").asText());
  verify(client,never()).upload(any());
  when(client.query(eq("/open/logisticscompany/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"lc_id\":\"SF.5\",\"lc_name\":\"顺丰到付\"}],\"has_next\":false}}"));
  when(client.upload(any())).thenAnswer(call->{
   var row=((JsonNode)call.getArgument(0)).get(0);assertEquals("SF.5",row.path("lc_id").asText());assertEquals("顺丰到付",row.path("logistics_company").asText());
   return json("{\"code\":0,\"data\":{\"datas\":[{\"so_id\":\""+row.path("so_id").asText()+"\",\"o_id\":123,\"issuccess\":true}]}}");
  });
  assertEquals("ACCEPTED",service.submit("o1",request,admin).path("status").asText());
  verify(client,times(2)).query(eq("/open/logisticscompany/query"),eq(Map.of("wms_co_id",10048859L,"page_index",1,"page_size",50)));
 }
 @Test void failedCarrierPreflightCannotBeRebuiltBySilentlyDroppingTheOriginalCarrier() throws Exception {
  var state=(ObjectNode)db.state(true);var order=(ObjectNode)state.path("orders").get(0);
  order.putObject("approvalDraft").put("logisticsMode","CARRIER").put("logisticsCode","SF.5").put("logisticsCompany","顺丰到付");
  var request=(ObjectNode)input();request.put("logisticsMode","CARRIER").put("logisticsCode","SF.5").put("logisticsCompany","顺丰到付");
  when(client.query(eq("/open/logisticscompany/query"),any())).thenThrow(new ApiException("物流目录不可用"));
  assertEquals("FAILED",service.submit("o1",request,admin).path("status").asText());
  order.remove("approvalDraft");
  assertThrows(ApiException.class,()->service.submit("o1",input(),admin));
  assertEquals("SF.5",json(persisted).path("request").path("lc_id").asText());verify(client,never()).upload(any());
 }
 @Test void partialCatalogFetchNeverReplacesProducts() throws Exception {
  when(client.query(eq("/open/sku/query"),any())).thenReturn(
      json("{\"code\":0,\"data\":{\"datas\":[{\"sku_id\":\"A\",\"enabled\":1}],\"has_next\":true}}"))
      .thenThrow(new ApiException("第二页不可用"));
  assertThrows(ApiException.class,()->service.syncProducts(admin,json("{\"keyword\":\"A\"}")));
  verify(db,never()).state(any(JsonNode.class));
 }
 @Test void syncPreservesOrdersAndUpdatesProductRevision() throws Exception {
  var result=service.syncProducts(admin,json("{\"keyword\":\"A\"}"));
  assertEquals("o1",result.path("orders").get(0).path("id").asText());
  assertEquals(1,result.path("revision").asLong());
  assertEquals("jushuitan",result.path("productSource").asText());
  assertEquals("A",result.path("products").get(0).path("jstSku").asText());
  verify(db).state(any(JsonNode.class));
 }
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
 @Test void creditShipmentCannotUseLegacySubmitOrFullCashAmendment()throws Exception{
  acceptedOrder();var record=(ObjectNode)m.readTree(persisted);record.put("creditFulfillment",true);persisted=record.toString();
  clearInvocations(client);
  assertThrows(ApiException.class,()->service.submit("o1",input(),admin));
  assertThrows(ApiException.class,()->service.amend("o1",input(),admin));
  assertThrows(ApiException.class,()->service.continueAmendment("o1",input(),admin));
  verify(client,never()).upload(any());verify(client,never()).mutate(anyString(),anyString(),any());
 }
 @Test void queuedSubmissionCannotRaceWithAnotherLegacyShipment()throws Exception{
  when(db.rows("SELECT order_id FROM jst_credit_pushes WHERE order_id=?","o1"))
      .thenReturn(List.of(Map.of("order_id","o1")));
  assertThrows(ApiException.class,()->service.submit("o1",input(),admin));
  assertNull(persisted);verify(client,never()).upload(any());
 }
 @Test void missingCreditOrderRemainsUnknownAndCannotEnableBlindResend()throws Exception{
  acceptedOrder();var record=(ObjectNode)m.readTree(persisted);
  record.put("creditFulfillment",true).put("upstreamAttempted",true).put("status","UNKNOWN").put("startedAt",0);persisted=record.toString();
  when(client.query(eq("/open/orders/single/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"orders\":[]}}"));
  assertEquals("UNKNOWN",service.sync("o1").path("status").asText());
 }
 @Test void nativeWaitPayIsExposedWithoutInventingShipment()throws Exception{
  acceptedOrder();upstreamOrder("WaitPay",null);
  var result=service.sync("o1");
  assertEquals("WaitPay",result.path("nativeStatus").asText());
  assertNotEquals("SHIPPED",result.path("status").asText());
 }
 @Test void explicitCreditRejectionIsNotReclassifiedAsAnUnknownWrite()throws Exception{
  acceptedOrder();var record=(ObjectNode)m.readTree(persisted);
  record.put("creditFulfillment",true).put("confirmedRejected",true).put("upstreamAttempted",true).put("status","FAILED").put("startedAt",0);persisted=record.toString();
  when(client.query(eq("/open/orders/single/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"orders\":[]}}"));
  assertEquals("FAILED",service.sync("o1").path("status").asText());
  assertTrue(m.readTree(persisted).path("confirmedRejected").asBoolean());
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
  ObjectNode order;
  try {order=((ObjectNode)m.readTree(persisted).path("request")).deepCopy();} catch(Exception e){throw new RuntimeException(e);}
  order.put("o_id",123).put("status",status);
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
  var original=m.readTree(persisted);
  when(db.rows(contains("payment_methods"),eq("银行转账"))).thenReturn(List.of(Map.of("id","bank")));
  when(client.mutate(anyString(),eq("/open/jushuitan/orderbyoid/cancel"),any())).thenAnswer(i->{oldCancelled=true;return json("{\"code\":0}");});
  when(client.query(eq("/open/orders/single/query"),any())).thenAnswer(i->{
   var params=(JsonNode)i.getArgument(1);String so=params.path("so_ids").get(0).asText();
   var orders=m.createArrayNode();
   if(newRequest!=null&&newRequest.path("so_id").asText().equals(so)) {
    var actual=((ObjectNode)newRequest).deepCopy();actual.put("o_id",456).put("status","WaitConfirm");orders.add(actual);
   } else if(so.equals(original.path("soId").asText())) {
    var old=m.createObjectNode().put("o_id",123).put("so_id",so).put("pay_amount",10).put("status",oldCancelled?"Cancelled":"WaitConfirm");
    old.setAll((ObjectNode)original.path("request"));old.put("status",oldCancelled?"Cancelled":"WaitConfirm");orders.add(old);
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

 @Test void manualCancellationAllowsReplacementWithAnotherWarehouse() throws Exception {
  amendmentSetup();
  oldCancelled = true;
  when(client.changesEnabled()).thenReturn(false);
  when(client.query(eq("/open/wms/partner/query"), any())).thenReturn(json("""
      {"code":0,"data":{"datas":[{"wms_co_id":10048860,"status":"生效","name":"备用仓"}]}}
      """));
  var body = amendmentInput(10).put("manualCancellation", true).put("warehouseId", 10048860);

  var prepared = service.amend("o1", body, admin);
  assertEquals("READY", prepared.path("amendment").path("status").asText());
  assertTrue(prepared.path("amendment").hasNonNull("cancellationVerifiedAt"));
  assertEquals("CANCELLED", prepared.path("status").asText());
  verify(client, times(1)).upload(any());

  var replaced = service.continueAmendment("o1", m.createObjectNode(), admin);
  assertEquals("COMPLETE", replaced.path("amendment").path("status").asText());
  assertEquals(10048860, newRequest.path("wms_co_id").asInt());
  assertEquals(123, replaced.path("amendment").path("oldOId").asInt());
  assertEquals(456, replaced.path("oId").asInt());
  verify(client, never()).mutate(anyString(), anyString(), any());
  verify(db, never()).receipt(any());
 }

 @Test void manualCancellationRequiresExactCancelledOrderAndChecksArchivedOutbound() throws Exception {
  amendmentSetup();
  var body = amendmentInput(10).put("manualCancellation", true);
  assertThrows(ApiException.class, () -> service.amend("o1", body, admin));
  assertFalse(m.readTree(persisted).has("amendment"));

  oldCancelled = true;
  service.amend("o1", body, admin);
  when(client.query(eq("/open/orders/out/simple/query"),
      argThat(filters -> filters instanceof JsonNode request && request.path("archive").asBoolean())))
      .thenReturn(json("""
          {"code":0,"data":{"datas":[{"io_id":91,"status":"Archive"}]}}
          """));
  assertThrows(ApiException.class, () -> service.continueAmendment("o1", m.createObjectNode(), admin));
  verify(client, times(1)).upload(any());
  verify(client, never()).mutate(anyString(), anyString(), any());
  verify(db, never()).receipt(any());
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

 ObjectNode externalInput(JsonNode record) {
  var diff=record.path("externalDifference");return m.createObjectNode().put("id",diff.path("id").asText())
   .put("fingerprint",diff.path("fingerprint").asText()).put("version",record.path("editVersion").asText()).put("reason","已核对聚水潭修改");
 }
 JsonNode externalPrice(int amount) throws Exception {
  var actual=((ObjectNode)m.readTree(persisted).path("request")).deepCopy();actual.put("pay_amount",amount);
  ((ObjectNode)actual.path("items").get(0)).put("price",amount).put("amount",amount);
  upstreamOrder("WaitConfirm",actual);return service.sync("o1");
 }
 @Test void externalAddressCanBeAcceptedOnReadOnlyConnectionWithoutMutatingOriginal() throws Exception {
  acceptedOrder();when(client.changesEnabled()).thenReturn(false);when(client.uploadsEnabled()).thenReturn(false);
  upstreamOrder("WaitConfirm",m.createObjectNode().put("receiver_address","聚水潭新地址"));
  var result=service.sync("o1");assertEquals("PENDING",result.path("externalDifference").path("status").asText());
  var input=externalInput(result);result=service.confirmExternal("o1",input,admin);
  assertFalse(result.has("externalDifference"));assertEquals("聚水潭新地址",result.path("request").path("receiver_address").asText());
  assertEquals(1,result.path("externalChanges").size());
  service.confirmExternal("o1",input,admin); // stable ID replay must not append another history or receipt
  assertEquals(1,m.readTree(persisted).path("externalChanges").size());
  verify(db,never()).receipt(any());verify(db,never()).state(any(JsonNode.class));verify(client,never()).mutate(anyString(),anyString(),any());
 }
 @Test void externalFinancialChangeNeedsApprovalAndSignedCashDifference() throws Exception {
  acceptedOrder();when(db.rows(contains("payment_methods"),eq("银行转账"))).thenReturn(List.of(Map.of("id","bank")));
  var result=externalPrice(20);var pending=externalInput(result);result=service.confirmExternal("o1",pending,admin);
  assertEquals("AWAITING_FUNDS",result.path("externalDifference").path("status").asText());assertFalse(result.has("effectiveOrder"));verify(db,never()).receipt(any());
  var confirm=externalInput(result);confirm.setAll(settlement(10));
  assertThrows(ApiException.class,()->service.confirmExternal("o1",confirm,admin));
  result=service.confirmExternal("o1",confirm,finance);
  assertEquals(20,result.path("effectiveOrder").path("total").asInt());assertEquals(20,result.path("effectiveOrder").path("paid").asInt());
  assertEquals(10,m.readTree(persisted).path("snapshot").path("total").asInt());
  var receipt=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(db).receipt(receipt.capture());assertEquals(10,receipt.getValue().path("amount").asInt());
  service.confirmExternal("o1",confirm,finance);verify(db,times(1)).receipt(any());
  assertTrue(result.path("request").path("pay_amount").isNumber());assertTrue(result.path("request").path("items").get(0).path("qty").isNumber());
  assertEquals("ACCEPTED",service.sync("o1").path("status").asText());
  verify(client,times(1)).upload(any());verify(client,never()).mutate(anyString(),anyString(),any());
 }
 @Test void externalRefundIsNegativeReceiptAndNewerEditInvalidatesApproval() throws Exception {
  acceptedOrder();when(db.rows(contains("payment_methods"),eq("银行转账"))).thenReturn(List.of(Map.of("id","bank")));
  var result=externalPrice(5);var old=externalInput(result);externalPrice(7);
  assertThrows(ApiException.class,()->service.confirmExternal("o1",old,finance));verify(db,never()).receipt(any());
  result=service.sync("o1");var input=externalInput(result);input.setAll(settlement(3));
  service.confirmExternal("o1",input,finance);var receipt=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(db).receipt(receipt.capture());assertEquals(-3,receipt.getValue().path("amount").asInt());
 }
 @Test void outboundCoreDifferenceAndReadFailuresCannotBeAccepted() throws Exception {
  acceptedOrder();when(client.query(eq("/open/orders/out/simple/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"io_id\":1}]}}"));
  var result=externalPrice(20);assertEquals("BLOCKED",result.path("externalDifference").path("status").asText());
  var input=externalInput(result);assertThrows(ApiException.class,()->service.confirmExternal("o1",input,admin));
  when(client.query(eq("/open/orders/single/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"orders\":[]}}"));
  assertThrows(ApiException.class,()->service.confirmExternal("o1",input,admin));verify(db,never()).receipt(any());
 }
 @Test void externalEditReversionResolvesDifferenceAndKeepsHistory() throws Exception {
  acceptedOrder();externalPrice(20);upstreamOrder("WaitConfirm",null);
  var result=service.sync("o1");assertFalse(result.has("externalDifference"));assertEquals("RESOLVED",result.path("externalChanges").get(0).path("status").asText());
 }
 @Test void externalDifferenceRequiresOrderApprovalPermissionAndBlocksCrmWrites() throws Exception {
  acceptedOrder();var result=externalPrice(20);
  assertThrows(ApiException.class,()->service.confirmExternal("o1",externalInput(result),new Actor("sales","销售",Set.of("business.write"))));
  assertThrows(ApiException.class,()->service.change("o1",changeInput("REMARK"),admin));verify(client,never()).mutate(anyString(),anyString(),any());
 }

 @Test void equalTotalExternalProductChangeNeedsNoCashAndChangesCurrentExecutionOnly() throws Exception {
  acceptedOrder();var target=((ObjectNode)m.readTree(persisted).path("request")).deepCopy();
  ((ObjectNode)target.path("items").get(0)).put("qty",2).put("price",5);
  upstreamOrder("WaitConfirm",target);var result=service.sync("o1");
  result=service.confirmExternal("o1",externalInput(result),admin);
  assertEquals(2,result.path("effectiveOrder").path("items").get(0).path("qty").asInt());
  assertEquals(1,m.readTree(persisted).path("snapshot").path("items").get(0).path("qty").asInt());
  verify(db,never()).receipt(any());verify(db,never()).state(any(JsonNode.class));
 }
 @Test void furtherExternalEditWhileWaitingForFundsRequiresFreshApproval() throws Exception {
  acceptedOrder();var result=externalPrice(20);result=service.confirmExternal("o1",externalInput(result),admin);
  var old=externalInput(result);old.setAll(settlement(10));var fresh=externalPrice(30);
  assertEquals("PENDING",fresh.path("externalDifference").path("status").asText());assertFalse(fresh.path("externalDifference").has("approvedAt"));
  assertThrows(ApiException.class,()->service.confirmExternal("o1",old,finance));verify(db,never()).receipt(any());
 }

 @Test void externalJournalKeepsEveryObservedRevisionBeyondPollingMessageLimitAndExportsIt() throws Exception {
  acceptedOrder();for(int amount=11;amount<=45;amount++)externalPrice(amount);
  var record=m.readTree(persisted);assertEquals(50,record.path("events").size());
  assertEquals(69,record.path("externalTimeline").size());assertEquals(34,record.path("externalChanges").size());
  var first=record.path("externalTimeline").get(0);assertEquals("DETECTED",first.path("type").asText());
  assertEquals("o1",first.path("orderId").asText());assertEquals("SYSTEM_OBSERVER",first.path("operatorSource").asText());
  assertFalse(first.path("erpOperatorKnown").asBoolean());assertEquals(10,first.path("baselineBefore").path("pay_amount").asInt());
  assertEquals(11,first.path("observation").path("orders").get(0).path("pay_amount").asInt());assertFalse(first.path("time").asText().isBlank());
  var exported=service.externalHistory("o1",new Actor("reader","查看",Set.of("business.read")));
  assertEquals(record.path("externalTimeline"),exported.path("externalTimeline"));assertTrue(exported.path("coverage").asText().contains("不是聚水潭全部操作日志"));
  assertThrows(ApiException.class,()->service.externalHistory("o1",admin));verify(db,never()).receipt(any());
 }
 @Test void externalJournalPreservesApprovalConfirmationAndSignedSettlementWithActorsAndTimes() throws Exception {
  acceptedOrder();when(db.rows(contains("payment_methods"),eq("银行转账"))).thenReturn(List.of(Map.of("id","bank")));
  var result=externalPrice(5);result=service.confirmExternal("o1",externalInput(result),admin);
  var input=externalInput(result);input.setAll(settlement(5));result=service.confirmExternal("o1",input,finance);
  var timeline=result.path("externalTimeline");assertEquals(4,timeline.size());
  assertEquals("APPROVED",timeline.get(1).path("type").asText());assertEquals("admin",timeline.get(1).path("operator").asText());
  assertEquals("SETTLEMENT_RECORDED",timeline.get(2).path("type").asText());assertEquals(-5,timeline.get(2).path("difference").path("settlement").path("amount").asInt());
  assertEquals("CONFIRMED",timeline.get(3).path("type").asText());assertEquals("finance",timeline.get(3).path("operator").asText());
  assertEquals(10,timeline.get(3).path("baselineBefore").path("pay_amount").asInt());assertEquals(5,timeline.get(3).path("observation").path("orders").get(0).path("pay_amount").asInt());
  assertEquals(4,service.confirmExternal("o1",input,finance).path("externalTimeline").size());
 }
 @Test void invalidPayloadAndRejectedConfirmationRemainRecordedWithoutCashEntry() throws Exception {
  acceptedOrder();var target=((ObjectNode)m.readTree(persisted).path("request")).deepCopy();((ObjectNode)target.path("items").get(0)).put("amount",999);
  upstreamOrder("WaitConfirm",target);var result=service.sync("o1");var input=externalInput(result);
  assertThrows(ApiException.class,()->service.confirmExternal("o1",input,admin));
  var timeline=m.readTree(persisted).path("externalTimeline");assertEquals(2,timeline.size());
  assertEquals(999,timeline.get(0).path("observation").path("orders").get(0).path("items").get(0).path("amount").asInt());
  assertEquals("ATTEMPT_FAILED",timeline.get(1).path("type").asText());assertEquals("admin",timeline.get(1).path("operator").asText());
  assertFalse(timeline.get(1).path("details").path("result").asText().isBlank());verify(db,never()).receipt(any());
 }

 @Test void oldWriterCannotDropHistoryAndExistingJournalEntriesCannotBeEdited() throws Exception {
  acceptedOrder();externalPrice(20);var stale=(ObjectNode)m.readTree(persisted);stale.remove("externalTimeline");
  org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"store",stale);
  assertEquals(1,m.readTree(persisted).path("externalTimeline").size());
  var changed=(ObjectNode)m.readTree(persisted);((ObjectNode)changed.path("externalTimeline").get(0)).put("type","ERASED");
  assertThrows(ApiException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"store",changed));
  assertEquals("DETECTED",m.readTree(persisted).path("externalTimeline").get(0).path("type").asText());
 }

 @Test void settlementShopListKeepsGenuineOfflineZeroButNeverCoercesInvalidIdsIntoIt() throws Exception {
  when(client.query(eq("/open/shops/query"),any())).thenReturn(json("""
   {"code":0,"data":{"datas":[
    {"shop_id":0,"shop_name":"{线下}","shop_site":"线下店铺"},
    {"shop_id":9,"shop_name":"商城","shop_site":"商家自有商城"},
    {"shop_id":"17","shop_name":"门店","shop_site":"线下店铺"},
    {"shop_name":"missing"},{"shop_id":"bad"},{"shop_id":false},{"shop_id":-1},
    {"shop_id":0.0},{"shop_id":null},{"shop_id":9223372036854775808}
   ]}}
   """));
  var all=service.shops(false);assertEquals(3,all.size());assertEquals(0,all.get(0).path("id").asInt());
  assertEquals("{线下}",all.get(0).path("name").asText());
  var upload=service.shops(true);assertEquals(2,upload.size());
  assertTrue(upload.findValues("id").stream().allMatch(id->id.asLong()>0));
 }

}
