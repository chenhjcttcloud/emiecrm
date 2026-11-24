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
  private JsonNode products() throws Exception { return json("[{\"id\":\"p1\",\"name\":\"产品甲\",\"source\":\"jushuitan\",\"enabled\":true,\"jstSku\":\"SKU-1\",\"jstEnvironment\":\"sandbox\"}]"); }
  private ObjectNode input() throws Exception { return (ObjectNode)json("""
    {"shopId":10402930,"warehouseId":10048859,"items":[{"skuId":"SKU-1"}],"recipient":{"name":"测试","phone":"00000000000",
     "province":"广东省","city":"深圳市","district":"南山区","address":"虚拟测试地址"}}
    """); }
  @Test void requiresManualWarehouseAndRejectsSkuOverride() throws Exception {
    var missing=input();missing.remove("warehouseId");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order(),products(),missing,"CRM-id",true));
    var tampered=input();((ObjectNode)tampered.path("items").get(0)).put("skuId","OTHER");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order(),products(),tampered,"CRM-id",true));
    assertEquals(10048859,JushuitanOrderMapper.build(m,order(),products(),input(),"CRM-id",true).path("wms_co_id").asLong());
  }
  @Test void rejectsNonIntegralOrOutOfRangeShipmentIdentifiersInsteadOfTruncating() throws Exception {
    for (String key : new String[]{"shopId", "warehouseId"}) {
      for (String raw : new String[]{"15136229.9", "15136229.0", "1e3", "true", "false", "null",
          "0", "-1", "9223372036854775808", "-9223372036854775809", "[]", "{}", "\"15136229\""}) {
        var request = input(); request.set(key, json(raw));
        assertThrows(ApiException.class, () -> JushuitanOrderMapper.build(m,order(),products(),request,"CRM-id",true),
            key + "=" + raw);
        assertThrows(ApiException.class, () -> JushuitanOrderMapper.buildCredit(m,order(),products(),request,"CRM-id",true),
            "credit " + key + "=" + raw);
      }
      var missing = input(); missing.remove(key);
      assertThrows(ApiException.class, () -> JushuitanOrderMapper.build(m,order(),products(),missing,"CRM-id",true));
      assertThrows(ApiException.class, () -> JushuitanOrderMapper.buildCredit(m,order(),products(),missing,"CRM-id",true));
    }
  }
  @Test void preservesExactPositiveIntegerIdentifiersIncludingLongBoundary() throws Exception {
    for (long id : new long[]{1, 15136229, Long.MAX_VALUE}) {
      var request = input().put("shopId", id).put("warehouseId", id);
      var result = JushuitanOrderMapper.build(m,order(),products(),request,"CRM-id",true);
      assertEquals(id, result.path("shop_id").longValue());
      assertEquals(id, result.path("wms_co_id").longValue());
    }
  }
  @Test void payloadUsesAuthoritativeAmountsAndPreservesIdentity() throws Exception {
    var r=JushuitanOrderMapper.build(m,order(),products(),input(),"CRM-identity",true);
    assertEquals("CRM-identity-1",r.path("items").get(0).path("outer_oi_id").asText());
    assertEquals("19.98",r.path("pay").path("amount").asText());
    assertEquals(2,r.path("items").get(0).path("qty").asInt());
    assertEquals("SKU-1",r.path("items").get(0).path("sku_id").asText());
    assertTrue(r.path("remark").asText().contains("禁止实际发货"));
  }
  @Test void customerBuyerIdentityStaysStableAcrossOrdersAndRenames() throws Exception {
    var first = JushuitanOrderMapper.build(m,order(),products(),input(),"CRM-order-1",true);
    var renamed = order().put("id","o2").put("customer","客户已改名");
    var second = JushuitanOrderMapper.build(m,renamed,products(),input(),"CRM-order-2",true);
    assertEquals(first.path("shop_buyer_id"), second.path("shop_buyer_id"));
    assertNotEquals(first.path("so_id"), second.path("so_id"));
    assertNotEquals(first.path("pay").path("outer_pay_id"), second.path("pay").path("outer_pay_id"));
    assertTrue(first.path("shop_buyer_id").asText().length() <= 50);
    var other = order().put("customerId", "c2");
    assertNotEquals(first.path("shop_buyer_id"), JushuitanOrderMapper.build(m,other,products(),input(),"CRM-order-3",true).path("shop_buyer_id"));
    assertTrue(JushuitanOrderMapper.buyerAccount("长客户编号".repeat(100)).length() <= 50);
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

  @Test void explicitCarrierIsMappedOnlyFromTheOrdersImmutablePlanWithoutTouchingMoneyOrAddress() throws Exception {
    var order=order();var request=input();
    var planned=order.putObject("approvalDraft").put("logisticsMode","CARRIER").put("logisticsCode","SF.5").put("logisticsCompany","顺丰到付");
    for(String field:java.util.List.of("logisticsMode","logisticsCode","logisticsCompany"))request.set(field,planned.path(field));
    var before=order.deepCopy();var inputBefore=request.deepCopy();
    var result=JushuitanOrderMapper.build(m,order,products(),request,"CRM-logistics",true);
    assertEquals("SF.5",result.path("lc_id").asText());assertEquals("顺丰到付",result.path("logistics_company").asText());
    assertEquals("虚拟测试地址",result.path("receiver_address").asText());assertEquals("19.98",result.path("pay").path("amount").asText());
    assertEquals(before,order);assertEquals(inputBefore,request);
    var forged=request.deepCopy().put("logisticsCode","OTHER");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order,products(),forged,"CRM-logistics",true));
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order,products(),input(),"CRM-logistics",true));
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order(),products(),request,"CRM-logistics",true));
    order.put("paid",0);planned.put("creditFulfillment",true);
    assertEquals("SF.5",JushuitanOrderMapper.buildCredit(m,order,products(),request,"CRM-logistics",true).path("lc_id").asText());
    order.put("status","审批中");
    assertEquals("SF.5",JushuitanOrderMapper.buildSubmittedCredit(m,order,products(),request,"CRM-logistics",true).path("lc_id").asText());
  }

  @Test void legacyAndExplicitWarehouseDefaultProduceNoNativeLogisticsFields() throws Exception {
    var legacy=JushuitanOrderMapper.build(m,order(),products(),input(),"CRM-default",true);
    var planned=order();planned.putObject("approvalDraft").put("logisticsMode","WAREHOUSE").put("logisticsCode","").put("logisticsCompany","");
    var request=input().put("logisticsMode","WAREHOUSE").put("logisticsCode","").put("logisticsCompany","");
    var explicit=JushuitanOrderMapper.build(m,planned,products(),request,"CRM-default",true);
    for(var result:java.util.List.of(legacy,explicit)) {
      assertFalse(result.has("lc_id"));assertFalse(result.has("logistics_company"));result.remove("order_date");((ObjectNode)result.path("pay")).remove("pay_date");
    }
    assertEquals(legacy,explicit);
  }

  @Test void creditSettlementUsesFullErpAmountAndKeepsActualCrmAmountsUnchanged() throws Exception {
    for (String paid : new String[]{"0", "5.25", "19.98"}) {
      var o = order().put("customer", "真实客户甲"); o.set("paid", json(paid));
      var p = products(); var request = input();
      var originalOrder = o.deepCopy(); var originalProducts = p.deepCopy(); var originalRequest = request.deepCopy();
      var result = JushuitanOrderMapper.buildCredit(m,o,p,request,"CRM-credit",true);
      assertEquals(0, result.path("pay").path("amount").decimalValue().compareTo(new java.math.BigDecimal("19.98")));
      assertEquals("账期挂账（非实收）", result.path("pay").path("payment").asText());
      assertEquals("WAIT_SELLER_SEND_GOODS", result.path("shop_status").asText());
      assertEquals("CRM-credit-PAY", result.path("pay").path("outer_pay_id").asText());
      assertEquals("EMIE-CRM", result.path("pay").path("seller_account").asText());
      assertEquals("CRM-credit", result.path("pay").path("buyer_account").asText());
      var remark = result.path("remark").asText();
      assertTrue(remark.contains("客户：真实客户甲"));
      assertTrue(remark.contains("审批快照时CRM已收：" + new java.math.BigDecimal(paid).setScale(2).toPlainString()));
      assertTrue(remark.contains("审批快照应收：" + new java.math.BigDecimal("19.98").subtract(new java.math.BigDecimal(paid)).setScale(2).toPlainString()));
      assertTrue(remark.contains("账期挂账（非实收）结算金额：19.98"));
      assertTrue(remark.contains("不代表现金到账"));
      assertTrue(remark.contains("禁止实际发货"));
      assertEquals(JushuitanOrderMapper.buyerAccount("c1"), result.path("shop_buyer_id").asText());
      assertEquals(originalOrder, o); assertEquals(originalProducts, p); assertEquals(originalRequest, request);
    }
  }

  @Test void submittedCreditUsesIndependentAuthorizationWithoutClaimingFeishuApproval() throws Exception {
    for (String status : new String[]{"审批中", "草稿", "已批准"}) {
      var o = order().put("status",status).put("paid",0);
      o.putObject("approvalDraft").put("creditFulfillment",true);
      var before=o.deepCopy();var request=input();
      var result=JushuitanOrderMapper.buildSubmittedCredit(m,o,products(),request,"CRM-submitted",true);
      assertEquals(0,result.path("pay").path("amount").decimalValue().compareTo(o.path("total").decimalValue()));
      assertEquals("账期挂账（非实收）",result.path("pay").path("payment").asText());
      assertEquals("WAIT_SELLER_SEND_GOODS",result.path("shop_status").asText());
      assertTrue(result.path("remark").asText().contains("财务须在聚水潭审核"));
      assertEquals(before,o);
      if (!"已批准".equals(status)) assertThrows(ApiException.class,
          ()->JushuitanOrderMapper.buildCredit(m,o,products(),request,"CRM-legacy",true));
    }
    for (String status : new String[]{"已取消","","驳回"}) {
      var o=order().put("status",status).put("paid",0);o.putObject("approvalDraft").put("creditFulfillment",true);
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildSubmittedCredit(m,o,products(),input(),"CRM-id",true));
    }
    var noIntent=order().put("status","审批中");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.buildSubmittedCredit(m,noIntent,products(),input(),"CRM-id",true));
  }

  @Test void requestFlagsCannotAuthorizeCreditOrOverrideAuthoritativePayment() throws Exception {
    var o = order().put("paid", 0);
    var request = input().put("creditFulfillment",true).put("credit",true).put("paid",19.98);
    request.putObject("pay").put("amount",19.98).put("payment","现金");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,o,products(),request,"CRM-credit",true));
    var result = JushuitanOrderMapper.buildCredit(m,o,products(),request,"CRM-credit",true);
    assertEquals("账期挂账（非实收）", result.path("pay").path("payment").asText());
    assertTrue(result.path("remark").asText().contains("审批快照时CRM已收：0.00"));
    var legacy = JushuitanOrderMapper.build(m,order(),products(),request,"CRM-paid",true);
    assertEquals("CRM已收款", legacy.path("pay").path("payment").asText());
    assertFalse(legacy.path("remark").asText().contains("账期挂账"));
  }

  @Test void creditStillRequiresApprovedSalesOrderAndCustomer() throws Exception {
    for (var change : new String[][]{{"status","审批中"}, {"status","草稿"}, {"type","预收款"}, {"customerId",""}}) {
      var o = order().put(change[0],change[1]).put("paid",0);
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,o,products(),input(),"CRM-credit",true));
    }
  }

  @Test void creditRejectsInvalidOrExcessiveActualAmounts() throws Exception {
    for (String raw : new String[]{"-0.01", "19.99", "0.001", "\"not-money\"", "true", "[]", "{}"}) {
      var o = order(); o.set("paid",json(raw));
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,o,products(),input(),"CRM-credit",true), "paid="+raw);
    }
    for (String raw : new String[]{"0", "-19.98", "19.981", "\"not-money\"", "null", "[]"}) {
      var o = order().put("paid",0); o.set("total",json(raw));
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,o,products(),input(),"CRM-credit",true), "total="+raw);
    }
    var mismatched = order().put("total",20).put("paid",0);
    assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,mismatched,products(),input(),"CRM-credit",true));
  }

  @Test void creditAcceptsCompleteAddressWithoutSeparateRegionsAndLegacyStillRequiresThem() throws Exception {
    var request = input(); var recipient = (ObjectNode)request.path("recipient");
    recipient.remove(java.util.List.of("province","city","district"));
    recipient.put("address","广东省深圳市南山区科技园测试路1号");
    var result = JushuitanOrderMapper.buildCredit(m,order().put("paid",0),products(),request,"CRM-credit",true);
    assertEquals("广东省深圳市南山区科技园测试路1号", result.path("receiver_address").asText());
    assertEquals("测试", result.path("receiver_name").asText());
    assertEquals("00000000000", result.path("receiver_mobile").asText());
    assertFalse(result.has("receiver_state")); assertFalse(result.has("receiver_city")); assertFalse(result.has("receiver_district"));
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,order(),products(),request,"CRM-paid",true));
    var withRegions = JushuitanOrderMapper.buildCredit(m,order(),products(),input(),"CRM-credit",true);
    assertEquals("广东省", withRegions.path("receiver_state").asText());
    assertEquals("深圳市", withRegions.path("receiver_city").asText());
    assertEquals("南山区", withRegions.path("receiver_district").asText());
    recipient.put("address","万科星火");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order().put("paid",0),products(),request,"CRM-credit",true));
  }

  @Test void creditRejectsPartialBlankMalformedOrOversizeRecipientFields() throws Exception {
    for (String field : new String[]{"name","phone","address","province","city","district"}) {
      for (JsonNode invalid : new JsonNode[]{m.nullNode(),m.getNodeFactory().textNode(" "),m.getNodeFactory().numberNode(123),m.createArrayNode()}) {
        var request = input(); ((ObjectNode)request.path("recipient")).set(field,invalid);
        assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),products(),request,"CRM-credit",true),field+"="+invalid);
      }
      var tooLong = input(); ((ObjectNode)tooLong.path("recipient")).put(field,"长".repeat(field.equals("address") ? 201 : 51));
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),products(),tooLong,"CRM-credit",true),field+" too long");
      var missing = input(); ((ObjectNode)missing.path("recipient")).remove(field);
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),products(),missing,"CRM-credit",true),field+" missing");
    }
    var badPhone = input(); ((ObjectNode)badPhone.path("recipient")).put("phone","请联系客户");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),products(),badPhone,"CRM-credit",true));
    var boundary = input(); ((ObjectNode)boundary.path("recipient")).put("address","广东省深圳市南山区" + "路".repeat(191));
    assertEquals(200,JushuitanOrderMapper.buildCredit(m,order(),products(),boundary,"CRM-credit",true).path("receiver_address").asText().length());
  }

  @Test void creditKeepsSkuQuantityAndEnvironmentChecks() throws Exception {
    var tampered = input(); ((ObjectNode)tampered.path("items").get(0)).put("skuId","OTHER");
    assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),products(),tampered,"CRM-credit",true));
    for (String qty : new String[]{"0", "-1", "0.5", "1000000001", "invalid"}) {
      var o = order(); ((ObjectNode)o.path("items").get(0)).put("qty",qty);
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,o,products(),input(),"CRM-credit",true));
    }
    for (String[] change : new String[][]{{"source","legacy"},{"jstEnvironment","production"},{"jstSku",""}}) {
      var p = products(); ((ObjectNode)p.get(0)).put(change[0],change[1]);
      assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),p,input(),"CRM-credit",true));
    }
    var disabled = products(); ((ObjectNode)disabled.get(0)).put("enabled",false);
    assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),disabled,input(),"CRM-credit",true));
    assertThrows(ApiException.class,()->JushuitanOrderMapper.buildCredit(m,order(),products(),input(),"CRM-credit",false));
    var production = products(); ((ObjectNode)production.get(0)).put("jstEnvironment","production");
    var result = JushuitanOrderMapper.buildCredit(m,order().put("paid",0),production,input(),"CRM-credit",false);
    assertFalse(result.path("remark").asText().contains("沙箱测试"));
    assertEquals("账期挂账（非实收）",result.path("pay").path("payment").asText());
  }
}
