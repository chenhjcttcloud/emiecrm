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

/** Maps an authoritative receipt batch and its product snapshots to the income approval. */
final class FeishuReceiptApprovalForm {
  private static final Map<String, String> REQUIRED_FIELDS = Map.of(
      "项目", "textarea", "客户", "input", "收款发生日期", "date", "收款类别", "radioV2",
      "收款金额（元）", "amount", "归属人", "contact");
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000");
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

  private FeishuReceiptApprovalForm() {}

  static ArrayNode build(ObjectMapper mapper, JsonNode definition, JsonNode batch, String ownerOpenId) {
    var fields = fields(mapper, definition);
    if (ownerOpenId == null || !ownerOpenId.matches("ou_[A-Za-z0-9_-]{1,128}"))
      throw invalid("收款登记人尚未绑定飞书账号，请核对用户资料");
    String date = text(batch.path("receiptDate"), "收款发生日期", 10);
    if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid("请选择有效的收款发生日期");
    LocalDate receiptDate;
    try {
      receiptDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException error) {
      throw invalid("请选择有效的收款发生日期");
    }
    String category = text(batch.path("receiptCategory"), "收款类别", 50);
    String categoryValue = null;
    for (var option : fields.get("收款类别").path("option")) {
      if (!category.equals(option.path("text").asText())) continue;
      if (categoryValue != null) throw invalid("飞书收款审批模板的收款类别重复");
      categoryValue = option.path("value").asText();
    }
    if (categoryValue == null || categoryValue.isBlank())
      throw invalid("飞书收款审批模板不支持所选收款类别");
    boolean cny = false;
    for (var currency : fields.get("收款金额（元）").path("option").path("currencyRange"))
      cny |= "CNY".equals(currency.asText());
    if (!cny) throw invalid("飞书收款审批模板未启用人民币金额");
    if (fields.get("归属人").path("option").path("multi").asBoolean())
      throw invalid("飞书收款审批模板的归属人应为单选联系人");

    var amount = number(batch.path("amount"), "本次收款金额", MAX_MONEY, 2);
    if (amount.signum() <= 0) throw invalid("本次收款金额必须大于零");
    var form = mapper.createArrayNode();
    value(form, fields, "项目").put("value", project(batch, amount));
    value(form, fields, "客户").put("value", text(batch.path("customer"), "客户", 500));
    value(form, fields, "收款发生日期").put("value",
        receiptDate.atStartOfDay(SHANGHAI).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    value(form, fields, "收款类别").put("value", categoryValue);
    value(form, fields, "收款金额（元）").put("value", amount.setScale(2)).put("currency", "CNY");
    value(form, fields, "归属人").putArray("open_ids").add(ownerOpenId);
    if (fields.containsKey("备注")) {
      var rawNote = batch.path("note");
      if (!rawNote.isMissingNode() && !rawNote.isNull() && !rawNote.isTextual())
        throw invalid("收款备注格式无效");
      String note = rawNote.asText("").strip();
      if (note.length() > 2000) throw invalid("收款备注最多填写2000字");
      value(form, fields, "备注").put("value", "本次收款审批通过后才计入订单已收金额，不触发聚水潭发货。"
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
        throw invalid("飞书收款审批模板表单格式无效");
      }
    }
    if (widgets == null || !widgets.isArray()) throw invalid("飞书收款审批模板表单格式无效");
    var fields = new HashMap<String, JsonNode>();
    var ids = new HashSet<String>();
    for (var widget : widgets) {
      String name = widget.path("name").asText().strip();
      String id = widget.path("id").asText();
      if (id.isBlank() || !ids.add(id)) throw invalid("飞书收款审批模板字段编号缺失或重复");
      String expected = REQUIRED_FIELDS.get(name);
      if ("备注".equals(name)) expected = "textarea";
      if (expected == null) {
        if (widget.path("required").asBoolean() && !"text".equals(widget.path("type").asText()))
          throw invalid("飞书收款审批模板包含尚未支持的必填字段：" + name);
        continue;
      }
      if (!expected.equals(widget.path("type").asText()) || fields.putIfAbsent(name, widget) != null)
        throw invalid("飞书收款审批模板字段格式不匹配：" + name);
    }
    for (String name : REQUIRED_FIELDS.keySet())
      if (!fields.containsKey(name)) throw invalid("飞书收款审批模板缺少字段：" + name);
    return fields;
  }

  private static String project(JsonNode batch, BigDecimal amount) {
    var text = new StringBuilder("收款申请\nCRM 收款批次：")
        .append(text(batch.path("id"), "收款批次编号", 64)).append("\n登记人：")
        .append(text(batch.hasNonNull("operatorDisplayName") ? batch.path("operatorDisplayName") : batch.path("operator"), "登记人", 191)).append("\n收款方式：")
        .append(text(batch.path("method"), "收款方式", 100)).append("\n本次收款合计：")
        .append(amount.setScale(2).toPlainString()).append(" 元");
    var allocations = batch.path("allocations");
    if (!allocations.isArray() || allocations.isEmpty()) throw invalid("请至少选择一笔收款订单");
    var orderIds = new HashSet<String>();
    var allocated = BigDecimal.ZERO;
    for (var allocation : allocations) {
      String orderId = text(allocation.path("orderId"), "订单编号", 191);
      if (!orderIds.add(orderId)) throw invalid("同一收款批次不能重复分配订单");
      var payment = number(allocation.path("amount"), "订单本次收款金额", MAX_MONEY, 2);
      if (payment.signum() <= 0) throw invalid("订单本次收款金额必须大于零");
      allocated = allocated.add(payment);
      String orderNumber = OrderNumbers.display(allocation.path("orderSnapshot"));
      if (orderNumber.isEmpty()) orderNumber = orderId;
      text.append("\n\n订单：").append(orderNumber).append(" | 本次收款：")
          .append(payment.setScale(2).toPlainString()).append(" 元");
      if (!orderNumber.equals(orderId)) text.append(" | 内部记录ID：").append(orderId);
      String owner = allocation.path("orderSnapshot").path("owner").asText().strip();
      if (!owner.isEmpty()) text.append(" | 签单人：").append(owner);
      appendProducts(text, allocation.path("items"));
    }
    if (amount.compareTo(allocated) != 0) throw invalid("实际收款金额与订单分配合计不一致");
    return text.toString();
  }

  private static void appendProducts(StringBuilder text, JsonNode items) {
    if (!items.isArray()) throw invalid("收款产品快照格式无效");
    if (items.isEmpty()) {
      text.append("\n商品明细：未填写");
      return;
    }
    var sum = BigDecimal.ZERO;
    int index = 0;
    text.append("\n商品明细：");
    for (var item : items) {
      var quantity = number(item.path("qty"), "商品数量", MAX_QUANTITY, 6);
      if (quantity.signum() <= 0) throw invalid("商品数量必须大于零");
      var price = number(item.path("price"), "商品单价", MAX_MONEY, 2);
      var subtotal = quantity.multiply(price);
      sum = sum.add(subtotal);
      text.append("\n").append(++index).append(". ").append(text(item.path("name"), "商品名称", 500));
      String sku = item.path("sku").asText().strip();
      String model = item.path("model").asText().strip();
      if (!sku.isEmpty()) text.append(" | SKU：").append(sku);
      if (!model.isEmpty()) text.append(" | 规格：").append(model);
      text.append(" | 数量：").append(quantity.toPlainString())
          .append(" | 单价：").append(price.setScale(2).toPlainString())
          .append(" 元 | 小计：").append(subtotal.setScale(2, RoundingMode.HALF_UP).toPlainString()).append(" 元");
    }
    text.append("\n商品合计：").append(sum.setScale(2, RoundingMode.HALF_UP).toPlainString())
        .append(" 元（本次收款金额按上述订单分配为准）");
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
    if (!value.isTextual() || text.isEmpty() || text.length() > max) throw invalid("请填写有效的" + label);
    return text;
  }

  private static ObjectNode value(ArrayNode form, Map<String, JsonNode> fields, String name) {
    var widget = fields.get(name);
    return form.addObject().put("id", widget.path("id").asText()).put("type", widget.path("type").asText());
  }

  private static ApiException invalid(String message) { return new ApiException(422, message); }
}
