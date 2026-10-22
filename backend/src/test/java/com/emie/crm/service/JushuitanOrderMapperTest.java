package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JushuitanOrderMapperTest {
  private final ObjectMapper m = new ObjectMapper();
  private JsonNode json(String s) throws Exception { return m.readTree(s); }
  private ObjectNode order() throws Exception { return (ObjectNode)json("""
    {"id":"o1","customerId":"c1","status":"已批准","type":"销售订单","total":19.98,"paid":19.98,
    "items":[{"product":"p1","qty":"2","price":"9.99"}]}
    """); }
  private JsonNode products() throws Exception { return json("[{\"id\":\"p1\",\"name\":\"产品甲\"}]"); }
  private ObjectNode input() throws Exception { return (ObjectNode)json("""
    {"shopId":10402930,"items":[{"skuId":"SKU-1"}],"recipient":{"name":"测试","phone":"00000000000",
     "province":"广东省","city":"深圳市","district":"南山区","address":"虚拟测试地址"}}
    """); }
  @Test void payloadUsesAuthoritativeAmountsAndPreservesIdentity() throws Exception {
    var r=JushuitanOrderMapper.build(m,order(),products(),input(),"CRM-identity",true);
    assertEquals("CRM-identity-1",r.path("items").get(0).path("outer_oi_id").asText());
    assertEquals("19.98",r.path("pay").path("amount").asText());
    assertEquals(2,r.path("items").get(0).path("qty").asInt());
    assertEquals("SKU-1",r.path("items").get(0).path("sku_id").asText());
    assertTrue(r.path("remark").asText().contains("禁止实际发货"));
  }
  @Test void unapprovedOrUnpaidCannotCreateShippingOrder() throws Exception {
    var o=order();o.put("status","审批中");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,o,products(),input(),"CRM-id",true));
    o.put("status","已批准").put("paid",10);
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,o,products(),input(),"CRM-id",true));
  }
  @Test void rejectsFractionalQuantitiesAndTamperedTotal() throws Exception {
    var o=order();((ObjectNode)o.path("items").get(0)).put("qty","0.5");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,o,products(),input(),"CRM-id",true));
    var bad=order();bad.put("total",20).put("paid",20);
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,bad,products(),input(),"CRM-id",true));
  }
  @Test void requiresSkuAndCompleteRecipient() throws Exception {
    var i=input();((ObjectNode)i.path("items").get(0)).put("skuId","");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order(),products(),i,"CRM-id",true));
    var bad=input();((ObjectNode)bad.path("recipient")).remove("address");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order(),products(),bad,"CRM-id",true));
  }
}
