package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;

/** Immutable payment rows and their deterministic funding allocation; never records money by itself. */
final class ReceiptPaymentDetails {
  static final int MAX_LINES = 50;
  static final String COMBINED = "组合收款";
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private ReceiptPaymentDetails() {}

  static boolean hasDetails(JsonNode input) { return input.has("paymentDetails"); }

  /** New input is canonicalized; scalar approvals remain readable without altering their stored payload. */
  static ArrayNode read(JsonNode input) {
    JsonNode source;
    if (hasDetails(input)) source = input.path("paymentDetails");
    else {
      var legacy = JSON.arrayNode();
      legacy.addObject().setAll(java.util.Map.of("method", input.path("method"), "amount", input.path("amount"),
          "receiptDate", input.path("receiptDate")));
      source = legacy;
    }
    require(source.isArray() && !source.isEmpty() && source.size() <= MAX_LINES, "请填写1至50笔收款明细");
    var result = JSON.arrayNode();
    int number = 0;
    for (var row : source) {
      var method = row.path("method");
      require(row.isObject() && method.isTextual() && !method.asText().isBlank() && method.asText().length() <= 50
          && method.asText().chars().noneMatch(Character::isISOControl), "请选择有效的收款明细方式");
      require(!COMBINED.equals(method.asText()), "组合收款仅为汇总说明，请为每笔明细选择实际收款方式");
      String date = date(row.path("receiptDate"));
      result.addObject().put("lineNo", ++number).put("method", method.asText())
          .put("amount", money(row.path("amount"))).put("receiptDate", date);
    }
    var total = total(result);
    if (input.has("amount"))
      require(money(input.path("amount")).compareTo(total) == 0, "收款明细合计与本次收款金额不一致");
    return result;
  }

  static BigDecimal total(JsonNode details) {
    require(details.isArray() && !details.isEmpty() && details.size() <= MAX_LINES, "请填写1至50笔收款明细");
    var result = BigDecimal.ZERO;
    for (var line : details) result = result.add(money(line.path("amount")));
    require(result.compareTo(MAX_MONEY) <= 0, "收款明细合计超出金额范围");
    return result.setScale(2, RoundingMode.UNNECESSARY);
  }

  static String method(JsonNode details) {
    String first = details.path(0).path("method").asText();
    for (var line : details) if (!first.equals(line.path("method").asText())) return COMBINED;
    return first;
  }

  static String receiptDate(JsonNode details) {
    String result = "";
    for (var line : details) {
      String date = date(line.path("receiptDate"));
      if (date.compareTo(result) > 0) result = date;
    }
    require(!result.isEmpty(), "请填写收款日期");
    return result;
  }

  static BigDecimal creditTotal(JsonNode batch) {
    if (!hasDetails(batch)) return "预收款核销".equals(batch.path("method").asText()) ? money(batch.path("amount")) : BigDecimal.ZERO;
    var result = BigDecimal.ZERO;
    for (var line : read(batch)) if ("预收款核销".equals(line.path("method").asText())) result = result.add(money(line.path("amount")));
    return result;
  }

  /** Allocations follow the order displayed by the receipt form, then the original payment-row order. */
  static void freezeAllocations(JsonNode details, ArrayNode allocations) {
    var fundingTotal = total(details);
    require(!allocations.isEmpty() && allocations.size() <= 100, "请选择1至100笔收款订单");
    var ids = new HashSet<String>();
    var allocatedTotal = BigDecimal.ZERO;
    int cursor = 0;
    var remaining = money(details.get(0).path("amount"));
    for (var value : allocations) {
      require(value.isObject(), "收款订单分配格式无效");
      var allocation = (ObjectNode) value;
      var id = allocation.path("orderId");
      require(id.isTextual() && !id.asText().isBlank() && id.asText().length() <= 191 && ids.add(id.asText()),
          "收款订单不存在或重复");
      var outstanding = money(allocation.path("amount"));
      allocatedTotal = allocatedTotal.add(outstanding);
      var parts = allocation.putArray("paymentDetails");
      while (outstanding.signum() > 0) {
        require(cursor < details.size(), "订单分配合计超过收款明细合计");
        var part = outstanding.min(remaining);
        parts.addObject().put("lineNo", cursor + 1).put("amount", part.setScale(2));
        outstanding = outstanding.subtract(part);
        remaining = remaining.subtract(part);
        if (remaining.signum() == 0) {
          cursor++;
          if (cursor < details.size()) remaining = money(details.get(cursor).path("amount"));
        }
      }
    }
    require(allocatedTotal.compareTo(fundingTotal) == 0, "收款明细合计必须与订单分配合计一致");
  }

  static void validateFrozen(JsonNode batch) {
    require(hasDetails(batch), "收款明细快照缺失");
    require(batch.has("amount"), "收款汇总金额缺失");
    var details = read(batch);
    for (int i = 0; i < batch.path("paymentDetails").size(); i++) {
      var number = batch.path("paymentDetails").get(i).path("lineNo");
      require(number.isIntegralNumber() && number.canConvertToInt() && number.asInt() == i + 1, "收款明细序号已变化");
    }
    require(same(details, batch.path("paymentDetails")), "收款明细快照格式无效");
    require(method(details).equals(batch.path("method").asText())
        && receiptDate(details).equals(batch.path("receiptDate").asText()), "收款汇总与原明细不一致");
    require(batch.path("allocations").isArray(), "收款分配快照缺失");
    ArrayNode expected = batch.path("allocations").deepCopy();
    freezeAllocations(details, expected);
    for (int i = 0; i < expected.size(); i++)
      require(same(expected.get(i).path("paymentDetails"), batch.path("allocations").get(i).path("paymentDetails")),
          "收款明细与订单分配快照不一致");
  }

  /** Call after validateFrozen; each result is a real funding row, never the batch summary method. */
  static ArrayNode parts(JsonNode batch, JsonNode allocation) {
    var details = read(batch);
    var result = JSON.arrayNode();
    for (var part : allocation.path("paymentDetails")) {
      int index = part.path("lineNo").asInt() - 1;
      require(index >= 0 && index < details.size(), "收款明细分配序号无效");
      var line = details.get(index);
      result.addObject().put("lineNo", index + 1).put("method", line.path("method").asText())
          .put("amount", money(part.path("amount"))).put("receiptDate", line.path("receiptDate").asText());
    }
    return result;
  }

  private static String date(JsonNode value) {
    require(value.isTextual() && value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "请填写有效的收款日期");
    try { LocalDate.parse(value.asText()); return value.asText(); }
    catch (DateTimeParseException invalid) { throw new ApiException("请填写有效的收款日期"); }
  }

  private static BigDecimal money(JsonNode value) {
    require((value.isNumber() || value.isTextual()) && !value.asText().isBlank() && value.asText().length() <= 64,
        "收款明细金额须大于零且最多两位小数");
    try {
      var result = new BigDecimal(value.asText().strip());
      require(result.signum() > 0 && result.compareTo(MAX_MONEY) <= 0 && result.stripTrailingZeros().scale() <= 2,
          "收款明细金额须大于零且最多两位小数");
      return result.setScale(2, RoundingMode.UNNECESSARY);
    } catch (NumberFormatException | ArithmeticException invalid) {
      throw new ApiException("收款明细金额须大于零且最多两位小数");
    }
  }

  private static boolean same(JsonNode left, JsonNode right) {
    return left.equals((a, b) -> a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, right);
  }
}
