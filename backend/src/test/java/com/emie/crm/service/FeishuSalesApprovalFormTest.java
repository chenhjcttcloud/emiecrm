package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class FeishuSalesApprovalFormTest {
  @Test void businessNumberIsVisibleWhileTheInternalIdRemainsInTheApprovalNote() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.withArray("form").addObject().put("id", "order-number").put("name", "订单编号").put("type", "input");
    var order = FeishuApprovalFixtures.order().put("orderNumber", "2026-11-00000001");
    var original = order.deepCopy();
    var form = build(definition, order);
    assertEquals("2026-11-00000001", field(form, "order-number").path("value").asText());
    String note = field(form, "note").path("value").asText();
    assertTrue(note.contains("CRM 订单：2026-11-00000001"));
    assertTrue(note.contains("内部记录ID：o1"));
    assertEquals(original, order);
  }

  @Test void aVerifiedPlannedWarehouseIsVisibleInTheExistingApprovalNote() throws Exception {
    var order = FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "华南发货仓");
    var form = build(FeishuApprovalFixtures.definition(), order);
    assertTrue(field(form, "note").path("value").asText().contains("计划发货仓库：华南发货仓（5）"));
    assertTrue(field(form, "note").path("value").asText().contains("订单备注"));
  }

  @Test void plannedCarrierIsVisibleWhileTheDefaultDoesNotInventAnyCarrier() throws Exception {
    var definition=FeishuApprovalFixtures.definition();var order=FeishuApprovalFixtures.order();
    String defaultNote=field(build(definition,order),"note").path("value").asText();
    assertTrue(defaultNote.contains("计划物流：由仓库安排"));assertFalse(order.has("approvalDraft"));
    order.putObject("approvalDraft").put("logisticsMode","CARRIER").put("logisticsCode","CUSTOM_7").put("logisticsCompany","已核验物流公司");
    var before=order.deepCopy();String selected=field(build(definition,order),"note").path("value").asText();
    assertTrue(selected.contains("计划物流：已核验物流公司（CUSTOM_7）"));assertFalse(selected.contains("计划物流：由仓库安排"));
    assertEquals(before,order);
  }

  @Test void anExplicitCarrierCannotBeHiddenByATemplateWithoutNotes() throws Exception {
    var definition=FeishuApprovalFixtures.definition();definition.withArray("form").remove(4);
    definition.withArray("form").addObject().put("id","order-id").put("name","订单编号").put("type","input");
    var order=FeishuApprovalFixtures.order();order.putObject("approvalDraft").put("logisticsMode","CARRIER")
        .put("logisticsCode","CUSTOM_7").put("logisticsCompany","已核验物流公司");
    assertTrue(assertThrows(ApiException.class,()->build(definition,order)).getMessage().contains("物流"));
  }

  @Test void creditApproversSeeTheExactShopRecipientAndNonCashAccountingBasis() throws Exception {
    var order=FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("creditFulfillment",true).put("shopId","15").put("shopName","国内线下店铺")
        .put("warehouseId","5").put("warehouseName","华南发货仓")
        .put("recipientName","真实收货人").put("recipientPhone","13800000000")
        .put("recipientProvince","广东省").put("recipientCity","深圳市").put("recipientDistrict","龙岗区").put("recipientAddress","万科星火online10栋");
    var original=order.deepCopy();
    var definition=FeishuApprovalFixtures.definition();
    definition.withArray("form").addObject().put("id","shipping-address").put("name","收货地址").put("type","input").put("required",true);
    var form=build(definition,order);
    String note=field(form,"note").path("value").asText();
    assertTrue(note.contains("聚水潭推送店铺：国内线下店铺（15）"));
    assertTrue(note.contains("账期挂账（非实收）"));assertTrue(note.contains("ERP付款状态显示已付"));
    assertTrue(note.contains("正式提交即推送聚水潭，飞书审批并行"));
    assertTrue(note.contains("财务直接在聚水潭审核，ERP审核后发货"));
    assertFalse(note.contains("飞书审批通过后自动推送"));
    assertTrue(note.contains("CRM实收与应收按真实收款独立记录"));assertTrue(note.contains("挂账不计入实收"));
    assertTrue(note.contains("真实收货人"));assertTrue(note.contains("13800000000"));assertTrue(note.contains("广东省深圳市龙岗区万科星火online10栋"));
    assertEquals("广东省深圳市龙岗区万科星火online10栋",field(form,"shipping-address").path("value").asText());
    assertEquals(original,order);
  }

  @Test void creditSubmissionRejectsMissingAndErpInvalidRecipientsWithoutRestrictingDraftSaving() throws Exception {
    for(String field:java.util.List.of("recipientName","recipientPhone","recipientAddress")) {
      for(String invalid:java.util.List.of("", "x".repeat(501))) {
        var order=FeishuApprovalFixtures.order();
        var draft=order.putObject("approvalDraft").put("creditFulfillment",true)
            .put("recipientName","收货人").put("recipientPhone","13800000000").put("recipientAddress","广东省深圳市龙岗区测试路10号");
        draft.put(field,invalid);
        if(invalid.isEmpty()) assertDoesNotThrow(()->JushuitanCreditPushPolicy.validateDraft(order));
        else assertThrows(ApiException.class,()->JushuitanCreditPushPolicy.validateDraft(order));
        assertThrows(ApiException.class,()->build(FeishuApprovalFixtures.definition(),order),field);
      }
    }
  }

  @Test void mergedAddressDoesNotRepeatMunicipalitiesOrDisplayAdministrativeGroupingLabels() {
    for(String city:java.util.List.of("北京市","市辖区","县")) {
      var draft=FeishuApprovalFixtures.MAPPER.createObjectNode().put("recipientProvince","北京市").put("recipientCity",city)
          .put("recipientDistrict","朝阳区").put("recipientAddress","测试路10号");
      assertEquals("北京市朝阳区测试路10号",JushuitanCreditPushPolicy.fullAddress(draft));assertEquals(city,draft.path("recipientCity").asText());
    }
    var direct=FeishuApprovalFixtures.MAPPER.createObjectNode().put("recipientProvince","湖北省").put("recipientCity","省直辖县级行政区划")
        .put("recipientDistrict","仙桃市").put("recipientAddress","测试路10号");
    assertEquals("湖北省仙桃市测试路10号",JushuitanCreditPushPolicy.fullAddress(direct));
  }

  @Test void aSelectedWarehouseCannotBeSilentlyOmittedFromATemplateWithoutNotes() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.withArray("form").remove(4);
    definition.withArray("form").addObject().put("id", "order-id").put("name", "订单编号").put("type", "input");
    var order = FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "华南发货仓");
    assertTrue(assertThrows(ApiException.class, () -> build(definition, order)).getMessage().contains("仓库"));
  }

  @Test void domesticTemplateMapsOrderAndLineAmountsWithoutAdditionalInput() throws Exception {
    var form = build(FeishuApprovalFixtures.definition(), FeishuApprovalFixtures.order());
    assertEquals("sale", field(form, "type").path("value").asText());
    assertEquals("CNY", field(form, "total").path("currency").asText());
    assertEquals("24", field(form, "total").path("value").asText());
    assertTrue(field(form, "note").path("value").asText().contains("o1"));
    var line = field(form, "items").path("value").get(0);
    assertEquals("黑色", field(line, "model").path("value").asText());
    assertEquals("24", field(line, "subtotal").path("value").asText());
  }
  @Test void rejectsUnknownRequiredFieldsWithTheirName() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.withArray("form").addObject().put("id", "other").put("name", "财务项目").put("type", "radioV2").put("required", true);
    var error = assertThrows(ApiException.class, () -> build(definition, FeishuApprovalFixtures.order()));
    assertTrue(error.getMessage().contains("财务项目"));
  }
  @Test void refusesTotalsThatDifferFromProducts() throws Exception {
    var order = FeishuApprovalFixtures.order().put("total", 25);
    assertThrows(ApiException.class, () -> build(FeishuApprovalFixtures.definition(), order));
  }
  @Test void prepaymentDoesNotRequireProductsOrAnEqualProductTotal() throws Exception {
    var order = FeishuApprovalFixtures.order().put("type", "预收款订单").put("total", 50);
    order.putArray("items");
    var form = build(FeishuApprovalFixtures.definition(), order);
    assertEquals("deposit", field(form, "type").path("value").asText());
    assertEquals("50", field(form, "total").path("value").asText());
    assertNull(field(form, "items"));
  }
  @Test void doesNotSilentlyIgnoreARequiredRecipient() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.withArray("form").addObject().put("id", "recipient").put("name", "收货人").put("type", "input").put("required", true);
    assertTrue(assertThrows(ApiException.class, () -> build(definition, FeishuApprovalFixtures.order())).getMessage().contains("收货人"));
  }
  @Test void acceptsJsonEncodedDefinitions() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.put("form", definition.path("form").toString());
    assertFalse(build(definition, FeishuApprovalFixtures.order()).isEmpty());
  }
  private JsonNode build(JsonNode definition, JsonNode order) throws Exception {
    return FeishuSalesApprovalForm.build(FeishuApprovalFixtures.MAPPER, definition, order,
        FeishuApprovalFixtures.state().path("products"), FeishuApprovalFixtures.MAPPER.createObjectNode());
  }
  private JsonNode field(JsonNode fields, String id) {
    for (var field : fields) if (id.equals(field.path("id").asText())) return field;
    return null;
  }
}
