package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Maps a deposit request to the dedicated income approval form, without recording a receipt. */
final class FeishuPrepaymentApprovalForm {
  private static final Map<String, String> REQUIRED_FIELDS = Map.of(
      "项目", "textarea", "客户", "input", "收款发生日期", "date", "收款类别", "radioV2",
      "收款金额（元）", "amount", "归属人", "contact");
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000");
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

  private FeishuPrepaymentApprovalForm() {}

  static ArrayNode build(ObjectMapper mapper, JsonNode definition, JsonNode order, JsonNode products,
      JsonNode input, String ownerOpenId) {
    if (!"预收款订单".equals(order.path("type").asText()))
      throw invalid("仅预收款可使用预收款审批表单");
    var fields = fields(mapper, definition);
    if (ownerOpenId == null || !ownerOpenId.matches("ou_[A-Za-z0-9_-]{1,128}"))
      throw invalid("预收款签单人尚未唯一绑定飞书账号，请核对签单人");
    String date = text(input.path("receiptDate"), "收款发生日期", 10);
    if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid("请选择有效的收款发生日期");
    LocalDate receiptDate;
    try {
      receiptDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException error) {
      throw invalid("请选择有效的收款发生日期");
    }
    String category = text(input.path("receiptCategory"), "收款类别", 50);
    String categoryValue = null;
    for (var option : fields.get("收款类别").path("option")) {
      if (!category.equals(option.path("text").asText())) continue;
      if (categoryValue != null) throw invalid("飞书预收款审批模板的收款类别重复");
      categoryValue = option.path("value").asText();
    }
    if (categoryValue == null || categoryValue.isBlank())
      throw invalid("飞书预收款审批模板不支持所选收款类别");
    boolean cny = false;
    for (var currency : fields.get("收款金额（元）").path("option").path("currencyRange"))
      cny |= "CNY".equals(currency.asText());
    if (!cny) throw invalid("飞书预收款审批模板未启用人民币金额");

    var total = number(order.path("total"), "预收款金额", MAX_MONEY, 2);
    var form = mapper.createArrayNode();
    value(form, fields, "项目").put("value", project(order, products, total));
    value(form, fields, "客户").put("value", text(order.path("customer"), "客户", 500));
    value(form, fields, "收款发生日期").put("value",
        receiptDate.atStartOfDay(SHANGHAI).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    value(form, fields, "收款类别").put("value", categoryValue);
    value(form, fields, "收款金额（元）").put("value", total).put("currency", "CNY");
    value(form, fields, "归属人").putArray("open_ids").add(ownerOpenId);
    if (fields.containsKey("备注")) {
      String note = order.path("note").asText().strip();
      value(form, fields, "备注").put("value", "审批申请，不代表已登记实际收款。"
          + (note.isEmpty() ? "" : "\n" + note));
    }
    return form;
  }

  private static Map<String, JsonNode> fields(ObjectMapper mapper, JsonNode definition) {
    JsonNode widgets = definition.path("form");
    if (widgets.isTextual()) {
      try {
        widgets = mapper.readTree(widgets.textValue());
      } catch (JsonProcessingException error) {
        throw invalid("飞书预收款审批模板表单格式无效");
      }
    }
    if (!widgets.isArray()) throw invalid("飞书预收款审批模板表单格式无效");
    var fields = new HashMap<String, JsonNode>();
    var ids = new HashSet<String>();
    for (var widget : widgets) {
      String name = widget.path("name").asText().strip();
      String expected = REQUIRED_FIELDS.get(name);
      if ("备注".equals(name)) expected = "textarea";
      if (expected == null) {
        if (widget.path("required").asBoolean() && !"text".equals(widget.path("type").asText()))
          throw invalid("飞书预收款审批模板包含尚未支持的必填字段：" + name);
        continue;
      }
      String id = widget.path("id").asText();
      if (!expected.equals(widget.path("type").asText()) || id.isBlank() || !ids.add(id)
          || fields.putIfAbsent(name, widget) != null)
        throw invalid("飞书预收款审批模板字段格式不匹配：" + name);
    }
    for (String name : REQUIRED_FIELDS.keySet())
      if (!fields.containsKey(name)) throw invalid("飞书预收款审批模板缺少字段：" + name);
    return fields;
  }

  private static String project(JsonNode order, JsonNode products, BigDecimal total) {
    String id = text(order.path("id"), "订单编号", 191);
    var text = new StringBuilder("预收款申请\nCRM 订单：")
        .append(OrderNumbers.display(order));
    if (!OrderNumbers.display(order).equals(id)) text.append("\n内部记录ID：").append(id);
    text.append("\n签单人：")
        .append(text(order.path("owner"), "签单人", 100)).append("\n预收款金额：")
        .append(total.setScale(2).toPlainString()).append(" 元（独立于商品合计）");
    var lines = order.path("items");
    if (lines.isMissingNode() || lines.isNull() || (lines.isArray() && lines.isEmpty()))
      return text.append("\n商品明细：未填写").toString();
    if (!lines.isArray()) throw invalid("预收款产品明细格式无效");
    var catalog = new HashMap<String, JsonNode>();
    products.forEach(product -> catalog.put(product.path("id").asText(), product));
    var sum = BigDecimal.ZERO;
    int index = 0;
    text.append("\n商品明细：");
    for (var line : lines) {
      var product = catalog.get(line.path("product").asText());
      if (product == null) throw invalid("预收款审批包含不存在的商品");
      var quantity = number(line.path("qty"), "商品数量", MAX_QUANTITY, 6);
      if (quantity.signum() <= 0) throw invalid("商品数量必须大于零");
      var price = number(line.path("price"), "商品单价", MAX_MONEY, 2);
      var subtotal = quantity.multiply(price);
      sum = sum.add(subtotal);
      String sku = product.path("jstSku").asText();
      if (sku.isBlank()) sku = product.path("code").asText();
      text.append("\n").append(++index).append(". ")
          .append(text(product.path("name"), "商品名称", 500));
      if (!sku.isBlank()) text.append(" | SKU：").append(sku);
      if (!product.path("model").asText().isBlank())
        text.append(" | 规格：").append(product.path("model").asText());
      text.append(" | 数量：").append(quantity.toPlainString())
          .append(" | 单价：").append(price.toPlainString())
          .append(" 元 | 小计：").append(subtotal.setScale(2, RoundingMode.HALF_UP).toPlainString()).append(" 元");
    }
    return text.append("\n商品合计：").append(sum.setScale(2, RoundingMode.HALF_UP).toPlainString())
        .append(" 元").toString();
  }

  private static BigDecimal number(JsonNode value, String label, BigDecimal max, int scale) {
    String text = value.asText().strip();
    if ((!value.isNumber() && !value.isTextual()) || text.isEmpty() || text.length() > 64)
      throw invalid("请填写有效的" + label);
    try {
      var result = new BigDecimal(text).stripTrailingZeros();
      if (result.signum() < 0 || result.compareTo(max) > 0 || result.scale() > scale)
        throw invalid("请填写有效的" + label);
      return result;
    } catch (NumberFormatException error) {
      throw invalid("请填写有效的" + label);
    }
  }

  private static String text(JsonNode value, String label, int max) {
    String text = value.asText().strip();
    if (!value.isTextual() || text.isEmpty() || text.length() > max)
      throw invalid("请填写有效的" + label);
    return text;
  }

  private static ObjectNode value(ArrayNode form, Map<String, JsonNode> fields, String name) {
    var widget = fields.get(name);
    return form.addObject().put("id", widget.path("id").asText()).put("type", widget.path("type").asText());
  }

  private static ApiException invalid(String message) { return new ApiException(422, message); }
}
