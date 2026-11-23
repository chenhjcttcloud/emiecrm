package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/** Captured template structure with synthetic widget/option IDs and no organization identifiers. */
class FeishuDomesticApprovalTemplateTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test void salesTemplateIncludesEveryRequiredWidgetAndPreservesCrmPricing() throws Exception {
    var definition = definition("sales");
    var order = FeishuApprovalFixtures.order();
    var original = order.deepCopy();
    var input = details(); input.putArray("discounts").add("0.123456");
    var submitted = build(definition, order, input);
    assertEquals(10, submitted.size());
    assertEquals("测试收货人", field(submitted, definition, "收货人").path("value").asText());
    assertEquals("测试街道 1 号", field(submitted, definition, "收货地址").path("value").asText());
    for (String name : new String[]{"订单总金额", "应付金额"}) {
      var amount = field(submitted, definition, name);
      assertEquals(0, new BigDecimal("24").compareTo(amount.path("value").decimalValue()));
      assertEquals("CNY", amount.path("currency").asText());
    }
    var children = widget(definition.path("form"), "商品清单").path("children");
    var line = field(submitted, definition, "商品清单").path("value").get(0);
    assertEquals("FIXTURE-SKU", fieldById(line, widget(children, "SKU").path("id").asText()).path("value").asText());
    assertEquals("0.123456", fieldById(line, widget(children, "折扣").path("id").asText()).path("value").asText());
    assertEquals(0, new BigDecimal("24").compareTo(fieldById(line, widget(children, "小计").path("id").asText()).path("value").decimalValue()));
    assertEquals(original, order);
    assertTrue(FeishuSalesApprovalForm.recipientRequired(mapper, definition));
    assertTrue(FeishuSalesApprovalForm.discountRequired(mapper, definition));
  }

  @ParameterizedTest @ValueSource(strings={"recipientName", "recipientPhone", "recipientAddress"})
  void actualSalesTemplateRejectsAnyMissingRecipientField(String missing) throws Exception {
    var input = details(); input.remove(missing);
    assertThrows(ApiException.class, () -> build(definition("sales"), FeishuApprovalFixtures.order(), input));
  }

  @Test void actualSalesTemplateRequiresACompletePerLineDiscountArray() throws Exception {
    var input = details(); input.remove("discounts");
    assertThrows(ApiException.class, () -> build(definition("sales"), FeishuApprovalFixtures.order(), input));
    input.putArray("discounts");
    assertThrows(ApiException.class, () -> build(definition("sales"), FeishuApprovalFixtures.order(), input));
    input.withArray("discounts").add("0").add("0");
    assertThrows(ApiException.class, () -> build(definition("sales"), FeishuApprovalFixtures.order(), input));
  }

  @ParameterizedTest @ValueSource(strings={"-1", "0.1234567", "1000000000000", "", "abc"})
  void discountMustBeAnExplicitNonNegativeValueWithSixDecimalsAtMost(String discount) throws Exception {
    var input = details(); input.putArray("discounts").add(discount);
    assertThrows(ApiException.class, () -> build(definition("sales"), FeishuApprovalFixtures.order(), input));
  }

  @Test void zeroDiscountIsTheApprovalOnlyDefaultAndDoesNotZeroTheSubtotal() throws Exception {
    var definition = definition("sales"); var form = build(definition, FeishuApprovalFixtures.order(), details());
    var children = widget(definition.path("form"), "商品清单").path("children");
    var line = field(form, definition, "商品清单").path("value").get(0);
    assertEquals(0, fieldById(line, widget(children, "折扣").path("id").asText()).path("value").intValue());
    assertEquals(24, fieldById(line, widget(children, "小计").path("id").asText()).path("value").intValue());
  }

  @Test void pickupOrdersUseTheirTemplateOptionAndTheSameDetailedForm() throws Exception {
    var definition = definition("sales");
    var order = FeishuApprovalFixtures.order().put("type", "海外提货单");
    var form = build(definition, order, details());
    assertEquals("fixture-option-1", field(form, definition, "订单类型").path("value").asText());
    assertEquals(24, field(form, definition, "订单总金额").path("value").asInt());
  }

  @Test void recipientLengthsMatchTheVisibleFormLimits() throws Exception {
    for (var item : java.util.Map.of("recipientName", 100, "recipientPhone", 50, "recipientAddress", 500).entrySet()) {
      var input = details().put(item.getKey(), "字".repeat(item.getValue()+1));
      assertThrows(ApiException.class, () -> build(definition("sales"), FeishuApprovalFixtures.order(), input));
    }
  }

  @Test void actualPrepaymentShapeSupportsTheDateThreeCategoriesAndAuthoritativeContact() throws Exception {
    var definition = definition("prepayment");
    var order = FeishuApprovalFixtures.order().put("type", "预收款订单").put("total", 60); order.putArray("items");
    var input = mapper.createObjectNode().put("receiptDate", "2026-11-23").put("receiptCategory", "销售收入");
    var form = FeishuSalesApprovalForm.build(mapper, definition, order, FeishuApprovalFixtures.state().path("products"), input, "ou_fixture_owner");
    assertEquals(java.util.List.of("服务收入", "其他收入", "销售收入"), FeishuSalesApprovalForm.prepaymentCategories(mapper, definition));
    assertTrue(FeishuSalesApprovalForm.usesDedicatedPrepaymentForm(mapper, definition));
    assertEquals("2026-11-23T00:00:00+08:00", field(form, definition, "收款发生日期").path("value").asText());
    assertEquals("fixture-option-2", field(form, definition, "收款类别").path("value").asText());
    assertEquals("ou_fixture_owner", field(form, definition, "归属人").path("open_ids").get(0).asText());
    assertEquals(60, field(form, definition, "收款金额（元）").path("value").asInt());
    assertNull(field(form, definition, "附件"));
  }

  static ObjectNode definition(String type) throws Exception {
    try (var input = new ClassPathResource("feishu/domestic-" + type + "-definition.json").getInputStream()) {
      return (ObjectNode) FeishuApprovalFixtures.MAPPER.readTree(input);
    }
  }
  static ObjectNode details() {
    var input = FeishuApprovalFixtures.MAPPER.createObjectNode().put("recipientName", "测试收货人").put("recipientPhone", "13000000000")
        .put("recipientAddress", "测试街道 1 号");
    input.putArray("discounts").add("0"); return input;
  }
  private JsonNode build(JsonNode definition, JsonNode order, JsonNode input) throws Exception {
    return FeishuSalesApprovalForm.build(mapper, definition, order, FeishuApprovalFixtures.state().path("products"), input);
  }
  private JsonNode widget(JsonNode widgets, String name) {
    for (var widget : widgets) if (name.equals(widget.path("name").asText())) return widget;
    throw new AssertionError("Missing fixture field: " + name);
  }
  private JsonNode field(JsonNode form, JsonNode definition, String name) { return fieldById(form, widget(definition.path("form"), name).path("id").asText()); }
  private JsonNode fieldById(JsonNode form, String id) { for (var field : form) if (id.equals(field.path("id").asText())) return field; return null; }
}
