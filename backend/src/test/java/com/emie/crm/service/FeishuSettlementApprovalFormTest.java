package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Settlement summaries use the sales workflow without pretending to be a new shipment. */
class FeishuSettlementApprovalFormTest {
  @Test void actualSalesTemplateShowsAnExplicitSettlementSummaryAndItsVerifiedReferences() throws Exception {
    var definition = FeishuDomesticApprovalTemplateTest.definition("sales");
    var order = settlement();
    var original = order.deepCopy();
    var form = build(definition, order, FeishuApprovalFixtures.MAPPER.createObjectNode());

    assertEquals(10, form.size());
    assertEquals("fixture-option-0", field(form, definition, "订单类型").path("value").asText());
    for (String name : new String[]{"收货人", "收货电话", "收货地址"})
      assertEquals("不适用（聚水潭已发货结算）", field(form, definition, name).path("value").asText());
    for (String name : new String[]{"订单总金额", "应付金额"}) {
      assertEquals(0, new BigDecimal("30.50").compareTo(field(form, definition, name).path("value").decimalValue()));
      assertEquals("CNY", field(form, definition, name).path("currency").asText());
    }
    var details = field(form, definition, "商品清单").path("value");
    assertEquals(1, details.size());
    var line = details.get(0);
    var children = widget(definition.path("form"), "商品清单").path("children");
    assertTrue(field(line, children, "商品名称").path("value").asText().contains("结算"));
    assertTrue(field(line, children, "SKU").path("value").asText().contains("不适用"));
    assertEquals(1, field(line, children, "数量").path("value").intValue());
    assertEquals(0, field(line, children, "折扣").path("value").decimalValue().signum());
    for (String name : new String[]{"单价", "小计"})
      assertEquals(0, new BigDecimal("30.50").compareTo(field(line, children, name).path("value").decimalValue()));
    String note = field(form, definition, "备注").path("value").asText();
    for (String expected : new String[]{"聚水潭", "2026-11-20", "2026-11-21", "32", "30.5", "优惠1.50元", "70001", "70002", "10", "结算商品甲", "结算商品乙"})
      assertTrue(note.contains(expected), "Missing settlement evidence: " + expected);
    assertEquals(original, order, "Approval presentation must not turn the CRM settlement into an ordinary product order");
    assertTrue(order.path("items").isEmpty());
  }

  @Test void callerSuppliedProductDiscountAndRecipientValuesCannotOverrideTheSettlementSummary() throws Exception {
    var definition = FeishuDomesticApprovalTemplateTest.definition("sales");
    var input = FeishuApprovalFixtures.MAPPER.createObjectNode().put("recipientName", "伪造收货人")
        .put("recipientPhone", "伪造电话").put("recipientAddress", "伪造地址").put("total", 999);
    input.putArray("discounts").add("99");
    input.putArray("sales").addObject().put("id", "伪造销售单");
    var form = build(definition, settlement(), input);
    var expected = build(definition, settlement(), FeishuApprovalFixtures.MAPPER.createObjectNode());
    assertEquals(expected, form);
    assertFalse(form.toString().contains("伪造"));
  }

  @Test void settlementCannotLoseItsSalesEvidenceOrTemplateNote() throws Exception {
    var definition = FeishuDomesticApprovalTemplateTest.definition("sales");
    var order = settlement();
    order.withObject("settlement").remove("sales");
    assertThrows(ApiException.class, () -> build(definition, order, FeishuApprovalFixtures.MAPPER.createObjectNode()));
    order.withObject("settlement").putArray("sales");
    assertThrows(ApiException.class, () -> build(definition, order, FeishuApprovalFixtures.MAPPER.createObjectNode()));

    var widgets = definition.withArray("form");
    for (int index = widgets.size() - 1; index >= 0; index--)
      if ("备注".equals(widgets.get(index).path("name").asText())) widgets.remove(index);
    assertThrows(ApiException.class, () -> build(definition, settlement(), FeishuApprovalFixtures.MAPPER.createObjectNode()));
  }

  @Test void ordinarySalesStillRequireRealRecipientsAndMatchingProductAmounts() throws Exception {
    var definition = FeishuDomesticApprovalTemplateTest.definition("sales");
    assertThrows(ApiException.class, () -> build(definition, FeishuApprovalFixtures.order(),
        FeishuApprovalFixtures.MAPPER.createObjectNode()));
    var order = FeishuApprovalFixtures.order().put("total", 30);
    assertThrows(ApiException.class, () -> build(definition, order, FeishuDomesticApprovalTemplateTest.details()));
  }

  @Test void longProductSummariesKeepTheSalesReferencesAndClearlyDiscloseTruncation() throws Exception {
    var definition = FeishuDomesticApprovalTemplateTest.definition("sales");
    var order = settlement().put("note", "补".repeat(500));
    order.withObject("settlement").put("adjustmentReason", "差".repeat(500));
    var sale = (ObjectNode) order.path("settlement").path("sales").get(0);
    var products = sale.putArray("items");
    for (int index = 0; index < 20; index++)
      products.addObject().put("sku", "LONG-SKU-" + index).put("name", "商".repeat(250)).put("quantity", 2);

    var form = build(definition, order, FeishuApprovalFixtures.MAPPER.createObjectNode());
    String note = field(form, definition, "备注").path("value").asText();
    assertTrue(note.contains("销售单 70001；店铺 10；线上单号 ERP-A；金额 24"));
    assertTrue(note.contains("销售单 70002；店铺 10；线上单号 ERP-B；金额 8"));
    assertTrue(note.contains("商品摘要已截断"));
    assertTrue(note.contains("其余商品已省略"));
    assertTrue(note.contains("LONG-SKU-0"));
    assertFalse(note.contains("LONG-SKU-19"));
    assertTrue(note.length() <= 5000, "The final note, including CRM identification, must fit the template field");
  }

  private ObjectNode settlement() throws Exception {
    var order = FeishuApprovalFixtures.order().put("source", "jushuitan-settlement").put("id", "JS-fixture-settlement")
        .put("total", new BigDecimal("30.50"));
    order.putArray("items");
    order.set("settlement", FeishuApprovalFixtures.MAPPER.readTree("""
        {"cycle":"day","periodStart":"2026-11-20","periodEnd":"2026-11-21",
         "erpAmount":32,"adjustmentReason":"优惠1.50元","sales":[
           {"id":"70001","shopId":"10","soId":"ERP-A","source":"manual","shippedOn":"2026-11-20",
            "status":"Sent","amount":24,"items":[{"sku":"SKU-A","name":"结算商品甲","quantity":2}]},
           {"id":"70002","shopId":"10","soId":"ERP-B","source":"manual","shippedOn":"2026-11-21",
            "status":"Sent","amount":8,"items":[{"sku":"SKU-B","name":"结算商品乙","quantity":1}]}]}
        """));
    return order;
  }

  private JsonNode build(JsonNode definition, JsonNode order, JsonNode input) {
    return FeishuSalesApprovalForm.build(FeishuApprovalFixtures.MAPPER, definition, order,
        FeishuApprovalFixtures.MAPPER.createArrayNode(), input);
  }

  private JsonNode field(JsonNode form, JsonNode definition, String name) {
    var widgets = definition.isArray() ? definition : definition.path("form");
    String id = widget(widgets, name).path("id").asText();
    for (var field : form) if (id.equals(field.path("id").asText())) return field;
    throw new AssertionError("Missing submitted field: " + name);
  }

  private JsonNode widget(JsonNode widgets, String name) {
    for (var widget : widgets) if (name.equals(widget.path("name").asText())) return widget;
    throw new AssertionError("Missing fixture widget: " + name);
  }
}
