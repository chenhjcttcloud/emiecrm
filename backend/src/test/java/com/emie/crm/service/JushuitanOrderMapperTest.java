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
    {"id":"o1","customerId":"c1","customer":"客户甲","status":"已批准","type":"销售订单","total":19.98,"paid":19.98,
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

  @Test void everyNewUploadPathCarriesTheFixedSourceLabelWithoutChangingBusinessOrPaymentFields() throws Exception {
    for (boolean sandbox : new boolean[]{true, false}) for (String path : new String[]{"paid", "approved-credit", "submitted-credit"}) {
      String soId="N2611270001", note="请按客户要求分箱\n到货前电话联系";
      var original=order().put("id",soId).put("customer","客户真实名称").put("note",note);
      boolean credit=!path.equals("paid");
      if (credit) original.put("paid",0);
      if (path.equals("submitted-credit")) {
        original.put("status","审批中");original.putObject("approvalDraft").put("creditFulfillment",true);
      }
      var catalog=products().deepCopy();
      ((ObjectNode)catalog.get(0)).put("jstEnvironment",sandbox?"sandbox":"production");
      var input=input();var before=original.deepCopy();var catalogBefore=catalog.deepCopy();var inputBefore=input.deepCopy();
      var result=switch(path) {
        case "approved-credit" -> JushuitanOrderMapper.buildCredit(m,original,catalog,input,soId,sandbox);
        case "submitted-credit" -> JushuitanOrderMapper.buildSubmittedCredit(m,original,catalog,input,soId,sandbox);
        default -> JushuitanOrderMapper.build(m,original,catalog,input,soId,sandbox);
      };
      assertTrue(result.path("labels").isTextual());assertEquals("亿觅CRM推送",result.path("labels").asText());
      assertEquals(7,result.path("labels").asText().length());
      assertEquals(0,result.path("pay_amount").decimalValue().compareTo(original.path("total").decimalValue()));
      assertEquals(0,result.path("pay").path("amount").decimalValue().compareTo(original.path("total").decimalValue()));
      assertEquals(credit?"账期挂账（非实收）":"CRM已收款",result.path("pay").path("payment").asText());
      assertEquals(credit?0:19.98,original.path("paid").asDouble());
      assertEquals("客户真实名称",result.path("shop_buyer_id").asText());
      assertEquals((sandbox?"【CRM沙箱测试，禁止实际发货】":"")+note,result.path("remark").asText());
      assertEquals(soId,result.path("so_id").asText());
      assertEquals(soId+"-1",result.path("items").get(0).path("outer_oi_id").asText());
      assertEquals(soId+"-PAY",result.path("pay").path("outer_pay_id").asText());
      assertEquals(soId,result.path("pay").path("buyer_account").asText());
      assertEquals(10402930,result.path("shop_id").asLong());assertEquals(10048859,result.path("wms_co_id").asLong());
      assertEquals(before,original);assertEquals(catalogBefore,catalog);assertEquals(inputBefore,input);
    }
  }

  @Test void clientOrOrderLabelsCannotOverrideTheServerOwnedSourceMarker() throws Exception {
    for (String forged : new String[]{"null", "1", "[]", "{}", "\"别的标签,伪造跳财审\""}) {
      var original=order();var input=input();
      original.set("labels",json(forged));input.set("labels",json(forged));
      var before=original.deepCopy();var inputBefore=input.deepCopy();
      assertEquals("亿觅CRM推送",JushuitanOrderMapper.build(m,original,products(),input,"N2611270001",true).path("labels").asText());
      original.put("paid",0);
      assertEquals("亿觅CRM推送",JushuitanOrderMapper.buildCredit(m,original,products(),input,"N2611270001",true).path("labels").asText());
      original.put("status","审批中");original.putObject("approvalDraft").put("creditFulfillment",true);
      assertEquals("亿觅CRM推送",JushuitanOrderMapper.buildSubmittedCredit(m,original,products(),input,"N2611270001",true).path("labels").asText());
      assertEquals(before.path("labels"),original.path("labels"));assertEquals(inputBefore,input);
    }
  }

  @Test void buyerAccountUsesTheCustomerDisplayNameWhileOrderAndPaymentIdentitiesStaySeparate() throws Exception {
    var initial = order().put("customer", "爱品儿（授权定制）");
    var first = JushuitanOrderMapper.build(m,initial,products(),input(),"CRM-order-1",true);
    var renamed = initial.deepCopy().put("id","o2").put("customer","客户已改名");
    var second = JushuitanOrderMapper.build(m,renamed,products(),input(),"CRM-order-2",true);
    assertEquals("爱品儿（授权定制）",first.path("shop_buyer_id").asText());
    assertEquals("客户已改名",second.path("shop_buyer_id").asText());
    assertNotEquals(first.path("so_id"),second.path("so_id"));
    assertNotEquals(first.path("pay").path("outer_pay_id"),second.path("pay").path("outer_pay_id"));
    assertEquals("CRM-order-1",first.path("pay").path("buyer_account").asText());
    var sameName = initial.deepCopy().put("customerId","another-customer");
    assertEquals(first.path("shop_buyer_id"),JushuitanOrderMapper.build(m,sameName,products(),input(),"CRM-order-3",true).path("shop_buyer_id"));
    for(String invalid : new String[]{"", " ", "长".repeat(51), "客户\n甲"}) {
      var bad=order().put("customer",invalid);
      assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,bad,products(),input(),"CRM-invalid",true));
    }
    assertEquals("长".repeat(50),JushuitanOrderMapper.buyerAccount("长".repeat(50)));
  }

  @Test void productionRemarksContainOnlyTheBusinessNoteAndAnEmptyNoteStaysEmpty() throws Exception {
    var productionProducts=products().deepCopy();((ObjectNode)productionProducts.get(0)).put("jstEnvironment","production");
    var ordinary=order().put("note","请分箱包装，周五送达\n外箱贴客户标签");
    var before=ordinary.deepCopy();
    assertEquals(ordinary.path("note").asText(),JushuitanOrderMapper.build(m,ordinary,productionProducts,input(),"CRM-note",false).path("remark").asText());
    var credit=ordinary.deepCopy().put("paid",0);
    var result=JushuitanOrderMapper.buildCredit(m,credit,productionProducts,input(),"CRM-note-credit",false);
    assertEquals(ordinary.path("note").asText(),result.path("remark").asText());
    assertEquals("账期挂账（非实收）",result.path("pay").path("payment").asText());
    assertEquals(before,ordinary);
    ordinary.remove("note");
    assertEquals("",JushuitanOrderMapper.build(m,ordinary,productionProducts,input(),"CRM-empty",false).path("remark").asText());
    ordinary.put("note","长".repeat(1000));
    assertEquals(1000,JushuitanOrderMapper.build(m,ordinary,productionProducts,input(),"CRM-boundary",false).path("remark").asText().length());
    ordinary.put("note","长".repeat(1001));
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,ordinary,productionProducts,input(),"CRM-oversize",false));
    ordinary.set("note",m.createObjectNode());
    assertThrows(ApiException.class,()->JushuitanOrderMapper.build(m,ordinary,productionProducts,input(),"CRM-malformed",false));
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
      assertEquals("【CRM沙箱测试，禁止实际发货】",result.path("remark").asText());
      assertEquals("真实客户甲",result.path("shop_buyer_id").asText());
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
      assertEquals("【CRM沙箱测试，禁止实际发货】",result.path("remark").asText());
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
    assertEquals("【CRM沙箱测试，禁止实际发货】",result.path("remark").asText());
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
