package com.emie.crm.service;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JushuitanShipmentSummaryTest {
 final ObjectMapper m=new ObjectMapper();
 JsonNode json(String s)throws Exception{return m.readTree(s);}
 @Test void deduplicatesOutsAndExcludesCancelledOrUnconfirmed()throws Exception{
  var result=JushuitanShipmentSummary.shipments(m,json("""
   [{"io_id":1,"status":"Confirmed","items":[{"sku_id":"A","qty":1}]},
    {"io_id":1,"status":"Confirmed","items":[{"sku_id":"A","qty":1}]},
    {"io_id":2,"status":"Cancelled","items":[{"sku_id":"A","qty":100}]},
    {"io_id":3,"status":"WaitConfirm","items":[{"sku_id":"A","qty":100}]}]
   """));
  assertEquals(1,result.size());
  assertEquals("PARTIAL",JushuitanShipmentSummary.status(json("[{\"status\":\"Sent\"}]"),result,json("[{\"sku_id\":\"A\",\"qty\":2}]")));
 }
 @Test void aggregatesMultipleParcelsAgainstDuplicateSkuLines()throws Exception{
  var shipments=json("[{\"items\":[{\"skuId\":\"A\",\"quantity\":1}]},{\"items\":[{\"skuId\":\"A\",\"quantity\":2}]}]");
  var expected=json("[{\"sku_id\":\"A\",\"qty\":1},{\"sku_id\":\"A\",\"qty\":2}]");
  assertEquals("SHIPPED",JushuitanShipmentSummary.status(json("[{\"status\":\"Sent\"}]"),shipments,expected));
 }
 @Test void uploadAcceptanceDoesNotImplyShipment()throws Exception{
  assertEquals("ACCEPTED",JushuitanShipmentSummary.status(json("[{\"status\":\"WaitConfirm\"}]"),json("[]"),json("[{\"sku_id\":\"A\",\"qty\":1}]")));
  assertEquals("EXCEPTION",JushuitanShipmentSummary.status(json("[{\"status\":\"Question\"}]"),json("[]"),json("[]")));
 }
 @Test void revocationRemovesPreviouslyShippedQuantities()throws Exception{
  var shipments=JushuitanShipmentSummary.shipments(m,json("[{\"io_id\":1,\"status\":\"Cancelled\",\"items\":[{\"sku_id\":\"A\",\"qty\":1}]}]"));
  assertEquals("ACCEPTED",JushuitanShipmentSummary.status(json("[{\"status\":\"WaitConfirm\"}]"),shipments,json("[{\"sku_id\":\"A\",\"qty\":1}]")));
 }
 @Test void externalFulfillmentSurvivesDeletedPendingWarehouseOut()throws Exception{
  var orders=json("""
   [{"o_id":42,"status":"Sent","labels":"开放平台发货","l_id":"TEST-42","send_date":"2026-10-21 17:58:44","logistics_company":"测试物流","items":[{"sku_id":"A","qty":2}]}]
   """);
  var outs=json("[{\"o_id\":42,\"io_id\":1,\"status\":\"delete\",\"items\":[{\"sku_id\":\"A\",\"qty\":2}]}]");
  var result=JushuitanShipmentSummary.shipments(m,outs,orders);
  assertEquals(1,result.size());assertEquals("TEST-42",result.get(0).path("trackingNumber").asText());
  assertEquals("SHIPPED",JushuitanShipmentSummary.status(orders,result,json("[{\"sku_id\":\"A\",\"qty\":2}]")));
  assertEquals(0,JushuitanShipmentSummary.shipments(m,outs,json(orders.toString().replace("Sent","WaitConfirm"))).size());
 }
 @Test void externalFallbackDoesNotDuplicateWarehouseOrReviveRevokedShipment()throws Exception{
  var orders=json("[{\"o_id\":42,\"status\":\"Sent\",\"labels\":\"开放平台发货\",\"l_id\":\"TEST\",\"send_date\":\"2026-10-21\",\"items\":[{\"sku_id\":\"A\",\"qty\":1}]}]");
  var outs=json("[{\"o_id\":42,\"io_id\":1,\"status\":\"Confirmed\",\"items\":[{\"sku_id\":\"A\",\"qty\":1}]}]");
  assertEquals(1,JushuitanShipmentSummary.shipments(m,outs,orders).size());
  var unmarked=json(orders.toString().replace("开放平台发货",""));
  assertEquals(0,JushuitanShipmentSummary.shipments(m,json(outs.toString().replace("Confirmed","Cancelled")),unmarked).size());
 }
}
