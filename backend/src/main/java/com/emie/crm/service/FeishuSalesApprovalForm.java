package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves domestic template widget IDs by their labels, never by guessed fixed IDs. */
public final class FeishuSalesApprovalForm {
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000");
  private static final Map<String, String> FIELDS = Map.ofEntries(
      Map.entry("订单编号", "id"), Map.entry("CRM订单编号", "id"), Map.entry("订单类型", "type"),
      Map.entry("签单人", "owner"), Map.entry("客户名称", "customer"), Map.entry("客户", "customer"),
      Map.entry("订单总额", "total"), Map.entry("订单总金额", "total"), Map.entry("总金额", "total"),
      Map.entry("预收款金额", "total"), Map.entry("应付金额", "total"), Map.entry("备注", "note"),
      Map.entry("订单产品明细", "items"), Map.entry("产品明细", "items"), Map.entry("商品清单", "items"),
      Map.entry("收货人", "recipientName"), Map.entry("收货电话", "recipientPhone"), Map.entry("收货地址", "recipientAddress"));
  private static final Map<String, String> LINE_FIELDS = Map.ofEntries(
      Map.entry("产品名称", "name"), Map.entry("商品名称", "name"), Map.entry("SKU", "sku"),
      Map.entry("产品代码", "sku"), Map.entry("规格型号", "model"), Map.entry("数量", "qty"),
      Map.entry("销售价", "price"), Map.entry("单价", "price"), Map.entry("销售单价", "price"),
      Map.entry("总金额", "subtotal"), Map.entry("小计", "subtotal"), Map.entry("销售小计", "subtotal"),
      Map.entry("折扣", "discount"));

  private FeishuSalesApprovalForm() {}

  static boolean usesDedicatedPrepaymentForm(ObjectMapper mapper, JsonNode definition) {
    for (var widget : widgets(mapper, definition))
      if ("收款金额（元）".equals(widget.path("name").asText().strip())) return true;
    return false;
  }

  public static ArrayNode build(ObjectMapper mapper, JsonNode definition, JsonNode order, JsonNode products, JsonNode input) {
    return build(mapper, definition, order, products, input, "");
  }

  public static ArrayNode build(ObjectMapper mapper, JsonNode definition, JsonNode order, JsonNode products,
      JsonNode input, String ownerOpenId) {
    boolean prepayment = "预收款订单".equals(order.path("type").asText());
    require(prepayment || Set.of("销售订单", "海外提货单").contains(order.path("type").asText()), "仅销售订单、海外提货单和预收款可发起飞书审批");
    if (prepayment && usesDedicatedPrepaymentForm(mapper, definition))
      return FeishuPrepaymentApprovalForm.build(mapper, definition, order, products, input, ownerOpenId);
    var widgets = widgets(mapper, definition);
    var seen = new HashSet<String>();
    var mapped = new HashSet<String>();
    for (var widget : widgets) {
      validateId(widget, seen);
      String key = FIELDS.get(widget.path("name").asText().strip());
      if (key == null) {
        requireOptional(widget);
        continue;
      }
      mapped.add(key);
    }
    require(mapped.containsAll(Set.of("customer", "total")) && (mapped.contains("id") || mapped.contains("note"))
        && (prepayment || mapped.contains("items")), "飞书审批模板须包含客户、订单金额、订单编号或备注及产品明细，请管理员核对模板");
    var total = decimal(order.path("total"), "订单金额", MAX_MONEY, 2);
    var lines = order.path("items");
    require(lines.isArray() && (prepayment || !lines.isEmpty()), "订单产品明细无效");
    var catalog = new HashMap<String, JsonNode>();
    products.forEach(product -> catalog.put(product.path("id").asText(), product));
    BigDecimal sum = BigDecimal.ZERO;
    for (var line : lines) {
      require(catalog.containsKey(line.path("product").asText()), "审批订单包含不存在的商品");
      var qty = decimal(line.path("qty"), "商品数量", MAX_QUANTITY, 6);
      require(qty.signum() > 0, "商品数量必须大于零");
      sum = sum.add(qty.multiply(decimal(line.path("price"), "销售价", MAX_MONEY, 2)));
    }
    if (!prepayment) require(sum.setScale(2, RoundingMode.HALF_UP).compareTo(total) == 0, "订单总额与商品小计不一致");
    var form = mapper.createArrayNode();
    for (var widget : widgets) {
      String key = FIELDS.get(widget.path("name").asText().strip());
      if (key == null) continue;
      switch (key) {
        case "items" -> {
          requireType(widget, "fieldList");
          if (lines.isEmpty()) { requireOptional(widget); continue; }
          var details = mapper.createArrayNode();
          JsonNode children = widget.path("children");
          require(children.isArray(), "飞书产品明细模板格式无效");
          var childIds = new HashSet<String>();
          var childKeys = new HashSet<String>();
          for (var child : children) {
            validateId(child, childIds);
            String childKey = LINE_FIELDS.get(child.path("name").asText().strip());
            if (childKey == null) requireOptional(child); else childKeys.add(childKey);
          }
          require(childKeys.containsAll(Set.of("name", "qty", "price")), "飞书产品明细须包含产品名称、数量和销售价");
          boolean explicitDiscounts = false;
          for (var child : children)
            explicitDiscounts |= "discount".equals(LINE_FIELDS.get(child.path("name").asText().strip())) && child.path("required").asBoolean();
          if (explicitDiscounts || input.has("discounts"))
            require(input.path("discounts").isArray() && input.path("discounts").size() == lines.size(), "请逐项填写商品折扣");
          for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var product = catalog.get(line.path("product").asText());
            var detail = details.addArray();
            for (var child : children) {
              String childKey = LINE_FIELDS.get(child.path("name").asText().strip());
              if (childKey == null) continue;
              switch (childKey) {
                case "name", "sku", "model" -> {
                  String value = childKey.equals("sku") ? product.path("jstSku").asText(product.path("code").asText())
                      : product.path(childKey).asText();
                  if (childKey.equals("sku") && value.isBlank()) value = product.path("code").asText();
                  if (value.isBlank() && !child.path("required").asBoolean()) continue;
                  writeText(detail, child, value);
                }
                case "qty" -> writeNumber(detail, child, decimal(line.path("qty"), "数量", MAX_QUANTITY, 6));
                case "price" -> writeNumber(detail, child, decimal(line.path("price"), "销售价", MAX_MONEY, 2));
                case "subtotal" -> writeNumber(detail, child, new BigDecimal(line.path("qty").asText()).multiply(new BigDecimal(line.path("price").asText())));
                case "discount" -> writeNumber(detail, child, input.path("discounts").has(i)
                    ? decimal(input.path("discounts").get(i), "折扣", MAX_MONEY, 6) : BigDecimal.ZERO);
                default -> throw new IllegalStateException();
              }
            }
          }
          value(form, widget).set("value", details);
        }
        case "total" -> writeNumber(form, widget, total);
        case "type" -> {
          if ("radioV2".equals(widget.path("type").asText())) {
            String selected = null;
            for (var option : widget.path("option"))
              if (order.path("type").asText().equals(option.path("text").asText())
                  || (prepayment && "预收款".equals(option.path("text").asText()))) {
                require(selected == null, "飞书审批模板的订单类型选项重复"); selected = option.path("value").asText();
              }
            require(selected != null && !selected.isBlank(), "飞书审批模板缺少所选订单类型");
            value(form, widget).put("value", selected);
          } else writeText(form, widget, order.path("type").asText());
        }
        case "owner" -> {
          if ("contact".equals(widget.path("type").asText())) {
            require(!ownerOpenId.isBlank(), "签单人尚未唯一绑定启用的飞书账号");
            value(form, widget).putArray("open_ids").add(ownerOpenId);
          } else writeText(form, widget, order.path("owner").asText());
        }
        case "note" -> writeText(form, widget, "CRM 订单：" + order.path("id").asText()
            + (order.path("note").asText().isBlank() ? "" : "\n" + order.path("note").asText()));
        case "recipientName", "recipientPhone", "recipientAddress" -> {
          String entered = input.path(key).asText().strip();
          if (entered.isBlank()) { requireOptional(widget); continue; }
          int max = switch (key) { case "recipientName" -> 100; case "recipientPhone" -> 50; default -> 500; };
          require(entered.length() <= max, widget.path("name").asText() + "最多 " + max + " 字");
          writeText(form, widget, entered);
        }
        default -> writeText(form, widget, order.path(key).asText());
      }
    }
    return form;
  }

  static boolean requiresOwnerIdentity(ObjectMapper mapper, JsonNode definition) {
    for (var widget : widgets(mapper, definition))
      if ("签单人".equals(widget.path("name").asText().strip()) && "contact".equals(widget.path("type").asText())) return true;
    return usesDedicatedPrepaymentForm(mapper, definition);
  }

  static boolean recipientRequired(ObjectMapper mapper, JsonNode definition) {
    for (var widget : widgets(mapper, definition)) {
      String key = FIELDS.get(widget.path("name").asText().strip());
      if (key != null && key.startsWith("recipient") && widget.path("required").asBoolean()) return true;
    }
    return false;
  }

  static boolean discountRequired(ObjectMapper mapper, JsonNode definition) {
    for (var widget : widgets(mapper, definition)) {
      if (!"items".equals(FIELDS.get(widget.path("name").asText().strip()))) continue;
      for (var child : widget.path("children"))
        if ("discount".equals(LINE_FIELDS.get(child.path("name").asText().strip())) && child.path("required").asBoolean()) return true;
    }
    return false;
  }

  static List<String> prepaymentCategories(ObjectMapper mapper, JsonNode definition) {
    var result = new java.util.ArrayList<String>();
    for (var widget : widgets(mapper, definition))
      if ("收款类别".equals(widget.path("name").asText().strip()))
        for (var option : widget.path("option"))
          if (!option.path("text").asText().isBlank()) result.add(option.path("text").asText());
    return result;
  }

  private static JsonNode widgets(ObjectMapper mapper, JsonNode definition) {
    JsonNode form = definition.path("form");
    if (form.isTextual()) {
      try { form = mapper.readTree(form.asText()); }
      catch (Exception failure) { throw new ApiException(422, "飞书审批模板表单格式无效"); }
    }
    require(form.isArray(), "飞书审批模板表单格式无效");
    return form;
  }

  private static void validateId(JsonNode widget, Set<String> seen) {
    require(!widget.path("id").asText().isBlank() && seen.add(widget.path("id").asText()), "飞书审批模板字段编号缺失或重复");
  }

  private static void requireOptional(JsonNode widget) {
    if (widget.path("required").asBoolean() && !"text".equals(widget.path("type").asText()))
      throw new ApiException(422, "飞书审批模板存在未填写或尚未支持的必填字段：" + widget.path("name").asText());
  }

  private static void requireType(JsonNode widget, String... allowed) {
    require(List.of(allowed).contains(widget.path("type").asText()), "飞书审批模板字段类型不匹配：" + widget.path("name").asText());
  }

  private static ObjectNode value(ArrayNode form, JsonNode widget) {
    return form.addObject().put("id", widget.path("id").asText()).put("type", widget.path("type").asText());
  }

  private static void writeText(ArrayNode form, JsonNode widget, String text) {
    requireType(widget, "input", "textarea");
    require(text.length() <= 5000 && (!text.isBlank() || !widget.path("required").asBoolean()), "请填写" + widget.path("name").asText());
    value(form, widget).put("value", text);
  }

  private static void writeNumber(ArrayNode form, JsonNode widget, BigDecimal number) {
    requireType(widget, "amount", "number");
    var field = value(form, widget).put("value", number);
    if ("amount".equals(widget.path("type").asText())) {
      boolean cny = false;
      for (var currency : widget.path("option").path("currencyRange")) cny |= "CNY".equals(currency.asText());
      require(cny, "飞书审批模板的金额字段须启用人民币");
      field.put("currency", "CNY");
    }
  }

  private static BigDecimal decimal(JsonNode value, String label, BigDecimal max, int scale) {
    require((value.isNumber() || value.isTextual()) && value.asText().length() <= 64, "请填写有效的" + label);
    try {
      var number = new BigDecimal(value.asText()).stripTrailingZeros();
      require(number.signum() >= 0 && number.scale() <= scale && number.compareTo(max) <= 0, "请填写有效的" + label);
      return number.scale() < 0 ? number.setScale(0) : number;
    } catch (NumberFormatException failure) { throw new ApiException("请填写有效的" + label); }
  }
}
