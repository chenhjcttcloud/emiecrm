package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeishuReceiptCategoryTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode widget() {
    var widget = mapper.createObjectNode().put("id", "category").put("name", "收款类别").put("type", "radioV2")
        .put("required", true).put("enable_default_value", true).put("widget_default_value", "services");
    var options = widget.putArray("option");
    options.addObject().put("text", "服务收入").put("value", "services");
    options.addObject().put("text", "其他收入").put("value", "other");
    options.addObject().put("text", "销售收入").put("value", "$i18n-sales.选项/+=:");
    return widget;
  }

  private ObjectNode definition(JsonNode widget) {
    var definition = mapper.createObjectNode();
    definition.putArray("form").addObject().put("id", "note").put("name", "备注").put("type", "textarea");
    definition.withArray("form").add(widget);
    return definition;
  }

  @Test void usesTheSalesIncomeOptionValueRatherThanTheFirstOptionOrConfiguredDefault() {
    var widget = widget(); var original = widget.deepCopy();
    assertEquals("销售收入", FeishuReceiptCategory.SALES_INCOME);
    assertEquals("$i18n-sales.选项/+=:", FeishuReceiptCategory.salesIncomeValue(widget));
    assertEquals(original, widget);
    ((ObjectNode) widget.path("option").get(2)).put("value", "new-opaque-value");
    assertEquals("new-opaque-value", FeishuReceiptCategory.salesIncomeValue(widget));
  }

  @Test void readinessAndFormMappingResolveTheSameValueForArrayAndSerializedDefinitions() throws Exception {
    var definition = definition(widget()); var original = definition.deepCopy();
    assertEquals("$i18n-sales.选项/+=:", FeishuReceiptCategory.fromDefinition(mapper, definition));
    assertEquals(original, definition);
    definition.put("form", mapper.writeValueAsString(definition.path("form")));
    assertEquals("$i18n-sales.选项/+=:", FeishuReceiptCategory.fromDefinition(mapper, definition));
  }

  @ParameterizedTest @ValueSource(strings={"radio", "checkboxV2", "input", ""})
  void rejectsAChangedCategoryControlType(String type) {
    var widget = widget().put("type", type);
    assertEquals(422, assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(widget)).status());
  }

  @Test void requiresTheExactSalesIncomeLabelAndDoesNotFallBackToAnotherCategory() {
    var widget = widget();
    ((ArrayNode) widget.path("option")).remove(2);
    assertTrue(assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(widget)).getMessage().contains("销售收入"));
    widget.withArray("option").addObject().put("text", "销售收入 ").put("value", "trailing-space-label");
    assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(widget));
  }

  @Test void duplicateLabelsOrValuesAreAmbiguousEvenWhenTheSalesOptionIsPresent() {
    var duplicateLabel = widget();
    duplicateLabel.withArray("option").addObject().put("text", "销售收入").put("value", "another-sales");
    assertTrue(assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(duplicateLabel)).getMessage().contains("重复"));
    var duplicateValue = widget();
    ((ObjectNode) duplicateValue.path("option").get(0)).put("value", "$i18n-sales.选项/+=:");
    assertTrue(assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(duplicateValue)).getMessage().contains("重复"));
    var unrelatedDuplicate = widget();
    unrelatedDuplicate.withArray("option").addObject().put("text", "服务收入").put("value", "another-service");
    assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(unrelatedDuplicate));
  }

  @Test void malformedOptionsAndNonTextOrEmptyValuesCannotBecomeSubmittedCategoryValues() throws Exception {
    for (String json : new String[]{"null", "{}", "[]", "true", "0", "\"\"", "\" \"", "\"value\\nInjected\""}) {
      var widget = widget();
      ((ObjectNode) widget.path("option").get(2)).set("value", mapper.readTree(json));
      assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(widget));
    }
    for (String json : new String[]{"null", "{}", "[]", "[null]", "[\"bad\"]", "[{\"text\":42,\"value\":\"x\"}]"}) {
      var widget = widget(); widget.set("option", mapper.readTree(json));
      assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(widget));
    }
    var missingValue = widget(); ((ObjectNode) missingValue.path("option").get(2)).remove("value");
    assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(missingValue));
    assertThrows(ApiException.class, () -> FeishuReceiptCategory.salesIncomeValue(null));
  }

  @Test void readinessRejectsMissingDuplicateOrMalformedCategoryDefinitions() {
    var missing = mapper.createObjectNode(); missing.putArray("form");
    assertThrows(ApiException.class, () -> FeishuReceiptCategory.fromDefinition(mapper, missing));
    var duplicate = definition(widget()); duplicate.withArray("form").add(widget().put("id", "another-category"));
    assertTrue(assertThrows(ApiException.class, () -> FeishuReceiptCategory.fromDefinition(mapper, duplicate)).getMessage().contains("重复"));
    var duplicateId = definition(widget()); ((ObjectNode) duplicateId.path("form").get(0)).put("id", "category");
    assertThrows(ApiException.class, () -> FeishuReceiptCategory.fromDefinition(mapper, duplicateId));
    var noId = widget(); noId.remove("id");
    assertThrows(ApiException.class, () -> FeishuReceiptCategory.fromDefinition(mapper, definition(noId)));
    for (String form : new String[]{"null", "{}", "not JSON", "[null]"}) {
      var invalid = mapper.createObjectNode().put("form", form);
      assertEquals(422, assertThrows(ApiException.class, () -> FeishuReceiptCategory.fromDefinition(mapper, invalid)).status());
    }
  }
}
