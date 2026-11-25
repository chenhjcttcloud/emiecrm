package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;

/** CRM sales receipts use one business category, mapped to each live template's opaque option value. */
final class FeishuReceiptCategory {
  static final String SALES_INCOME = "销售收入";

  private FeishuReceiptCategory() {}

  private static void require(boolean condition, String message) {
    if (!condition) throw new ApiException(422, message);
  }

  static String salesIncomeValue(JsonNode categoryWidget) {
    require(categoryWidget != null && categoryWidget.isObject()
        && "radioV2".equals(categoryWidget.path("type").asText()), "飞书审批模板的收款类别须为单选radioV2");
    var options = categoryWidget.path("option");
    require(options.isArray() && !options.isEmpty(), "飞书审批模板的收款类别选项格式无效，请管理员核对");
    var labels = new HashSet<String>();
    var values = new HashSet<String>();
    String salesValue = null;
    for (var option : options) {
      var label = option.path("text");
      var value = option.path("value");
      require(option.isObject() && label.isTextual() && !label.asText().isBlank()
          && label.asText().chars().noneMatch(Character::isISOControl)
          && value.isTextual() && !value.asText().isBlank()
          && value.asText().chars().noneMatch(Character::isISOControl),
          "飞书审批模板的收款类别选项格式无效，请管理员核对");
      require(labels.add(label.asText()) && values.add(value.asText()), "飞书审批模板的收款类别选项重复，请管理员核对");
      if (SALES_INCOME.equals(label.asText())) salesValue = value.asText();
    }
    require(salesValue != null, "飞书审批模板未配置销售收入类别，请管理员核对");
    return salesValue;
  }

  static String fromDefinition(ObjectMapper mapper, JsonNode definition) {
    require(definition != null && definition.isObject(), "飞书审批模板表单格式无效");
    JsonNode widgets = definition.path("form");
    if (widgets.isTextual()) {
      try { widgets = mapper.readTree(widgets.textValue()); }
      catch (JsonProcessingException invalid) { throw new ApiException(422, "飞书审批模板表单格式无效"); }
    }
    require(widgets != null && widgets.isArray(), "飞书审批模板表单格式无效");
    JsonNode category = null;
    for (var widget : widgets) {
      require(widget.isObject(), "飞书审批模板表单格式无效");
      if (!"收款类别".equals(widget.path("name").asText().strip())) continue;
      require(category == null, "飞书审批模板的收款类别字段重复，请管理员核对");
      category = widget;
    }
    require(category != null, "飞书审批模板缺少收款类别字段，请管理员核对");
    var id = category.path("id");
    require(id.isTextual() && !id.asText().isBlank() && id.asText().chars().noneMatch(Character::isISOControl),
        "飞书审批模板的收款类别字段编号无效");
    for (var widget : widgets)
      if (widget != category) require(!id.asText().equals(widget.path("id").asText()), "飞书审批模板的收款类别字段编号重复");
    return salesIncomeValue(category);
  }
}
