package com.emie.crm.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class JushuitanOrderDifferenceTest {
 final ObjectMapper m=new ObjectMapper();ObjectNode record,actual;ArrayNode products;
 @BeforeEach void setup()throws Exception{
  record=(ObjectNode)m.readTree("""
  {"soId":"CRM-test","oId":123,"request":{"so_id":"CRM-test","shop_id":1,"pay_amount":20,"freight":0,"receiver_address":"原址","receiver_mobile":"12345678900","remark":"原备注","wms_co_id":5,"items":[{"sku_id":"A","qty":2,"price":10,"amount":20}]},"snapshot":{"id":"o1","customerId":"c1","total":20,"paid":20,"items":[{"product":"p1","qty":2,"price":10}]}}
  """);
  actual=((ObjectNode)record.path("request")).deepCopy();actual.put("o_id",123).put("status","WaitConfirm");
  products=(ArrayNode)m.readTree("[{\"id\":\"p1\",\"source\":\"jushuitan\",\"jstSku\":\"A\",\"jstEnvironment\":\"sandbox\"}]");
 }
 ObjectNode diff(){return JushuitanOrderDifference.detect(m,record,m.createArrayNode().add(actual),products,"sandbox");}
 @Test void numericRepresentationAndIrrelevantTimestampDoNotCreateChanges(){
  actual.put("pay_amount","20.00").put("modified","later");assertNull(diff());
 }
 @Test void duplicateLinesAndReorderingDoNotCreateChanges(){
  var items=actual.putArray("items");for(int i=0;i<2;i++)items.addObject().put("sku_id","A").put("qty",1).put("price",10).put("amount",10);
  assertNull(diff());
 }
 @Test void quantityAndMoneyChangeMapsSkuAndCalculatesDifference(){
  actual.put("pay_amount",30);((ObjectNode)actual.path("items").get(0)).put("qty",3).put("amount",30);
  var d=diff();assertTrue(d.path("coreChanged").asBoolean());assertEquals("10",d.path("difference").asText());
  assertEquals("p1",d.path("effectiveOrder").path("items").get(0).path("product").asText());
  assertEquals(20,d.path("beforeOrder").path("paid").asInt());assertTrue(d.path("blockedReasons").isEmpty());
 }
 @Test void zeroTotalDeltaStillDetectsChangedProductsAndPrices(){
  ((ObjectNode)actual.path("items").get(0)).put("qty",4).put("price",5);assertTrue(diff().path("coreChanged").asBoolean());
 }
 @Test void addressAndRemarkAreIndependentOfProductMapping(){
  products.removeAll();actual.put("receiver_address","新址").put("remark","");
  var d=diff();assertFalse(d.path("coreChanged").asBoolean());assertTrue(d.path("blockedReasons").isEmpty());assertEquals("",d.path("after").path("remark").asText());
 }
 @Test void missingOrMaskedRecipientsNeverOverwriteKnownData(){
  actual.remove("receiver_address");actual.put("receiver_mobile","123****8900");assertNull(diff());
 }
 @Test void unknownOrAmbiguousSkuOrWrongEnvironmentIsBlocked(){
  actual.put("pay_amount",30);((ObjectNode)actual.path("items").get(0)).put("qty",3).put("amount",30);
  products.removeAll();assertFalse(diff().path("blockedReasons").isEmpty());
 }
 @Test void invalidIdentityAndSplitOrdersAreBlocked(){
  actual.put("o_id",999);assertEquals("IDENTITY",diff().path("kind").asText());actual.put("o_id",123).put("so_id","another");assertEquals("IDENTITY",diff().path("kind").asText());
  assertEquals("IDENTITY",JushuitanOrderDifference.detect(m,record,m.createArrayNode().add(actual).add(actual),products,"sandbox").path("kind").asText());
 }
 @Test void missingMalformedOrInconsistentAmountsCannotBeConfirmed(){
  ((ObjectNode)actual.path("items").get(0)).remove("amount");assertEquals("INVALID",diff().path("kind").asText());
 }
 @Test void freightAndDiscountAreDisplayedButBlocked(){
  actual.put("freight",5).put("pay_amount",25);assertFalse(diff().path("blockedReasons").isEmpty());assertEquals("5",diff().path("difference").asText());
 }
 @Test void cancelledAndCompositeChangesRequireManualReview(){
  actual.put("remark","变化").put("status","Cancelled");assertFalse(diff().path("blockedReasons").isEmpty());
  actual.put("status","WaitConfirm");((ObjectNode)actual.path("items").get(0)).put("src_combine_sku_id","COMBO");assertFalse(diff().path("blockedReasons").isEmpty());
 }
 @Test void starsInOrdinaryRemarksAreNotMistakenForPrivacyMasking(){
  actual.put("remark","加急*重要");assertEquals("加急*重要",diff().path("after").path("remark").asText());
 }

}
