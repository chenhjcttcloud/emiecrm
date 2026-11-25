package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The proposed deposit rows are approval data; they never book cash or increase customer credit. */
final class PrepaymentPaymentDetails {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private PrepaymentPaymentDetails() {}

  static boolean hasDetails(JsonNode order) { return order.has("paymentDetails"); }

  static ArrayNode read(JsonNode order) {
    require("预收款订单".equals(order.path("type").asText()), "仅预收款订单可填写预收款明细");
    require(hasDetails(order), "预收款明细缺失");
    var input = JSON.objectNode();
    input.set("paymentDetails", order.path("paymentDetails"));
    input.set("amount", order.path("total"));
    var details = ReceiptPaymentDetails.read(input);
    for (var line : details)
      require(!"预收款核销".equals(line.path("method").asText()), "预收款明细不能使用预收款核销");
    return details;
  }

  /** Only editable proposals are normalized. Existing active/approved snapshots must not pass through here. */
  static void normalize(ObjectNode order) {
    var details = read(order);
    String method = ReceiptPaymentDetails.method(details), date = ReceiptPaymentDetails.receiptDate(details);
    if (order.has("method"))
      require(order.path("method").isTextual() && method.equals(order.path("method").asText()), "预收款收款方式汇总与明细不一致");
    var draft = order.path("approvalDraft");
    require(draft.isMissingNode() || draft.isObject(), "预收款审批信息格式无效");
    if (draft.has("receiptDate"))
      require(date.equals(draft.path("receiptDate").asText()), "预收款汇总日期须为明细最晚日期");
    order.set("paymentDetails", details);
    order.put("total", ReceiptPaymentDetails.total(details)).put("method", method);
    order.withObject("approvalDraft").put("receiptDate", date);
  }

  /** Allows scalar method to describe later actual cash while keeping the original proposed rows intact. */
  static void validateRows(JsonNode order) {
    var details = read(order);
    requireCanonical(details, order.path("paymentDetails"));
    require(ReceiptPaymentDetails.receiptDate(details).equals(order.path("approvalDraft").path("receiptDate").asText()),
        "预收款汇总日期须为明细最晚日期");
  }

  static ObjectNode approval(JsonNode order, JsonNode input) {
    var result = input.isObject() ? (ObjectNode) input.deepCopy() : JSON.objectNode();
    if (!hasDetails(order)) {
      require(!input.has("paymentDetails"), "审批与订单中的预收款明细不一致");
      return result;
    }
    validateRows(order);
    var details = read(order);
    String method = ReceiptPaymentDetails.method(details), date = ReceiptPaymentDetails.receiptDate(details);
    require(method.equals(order.path("method").asText()), "预收款收款方式汇总与明细不一致");
    require(input.has("paymentDetails") && same(details, input.path("paymentDetails")), "审批与订单中的预收款明细不一致");
    requireCanonical(details, input.path("paymentDetails"));
    require(date.equals(input.path("receiptDate").asText()), "预收款汇总日期须为明细最晚日期");
    result.set("paymentDetails", order.path("paymentDetails").deepCopy());
    result.put("receiptDate", date);
    return result;
  }

  static String description(JsonNode order) {
    var details = read(order);
    var text = new StringBuilder("\n收款日期汇总：").append(ReceiptPaymentDetails.receiptDate(details))
        .append("（取预收款明细最晚日期，仅用于飞书日期字段）\n逐笔预收款明细：");
    for (var line : details) text.append("\n第").append(line.path("lineNo").asInt()).append("笔 | 收款方式：")
        .append(line.path("method").asText()).append(" | 预收款金额：")
        .append(line.path("amount").decimalValue().setScale(2).toPlainString()).append(" 元 | 发生日期：")
        .append(line.path("receiptDate").asText());
    return text.toString();
  }

  private static boolean same(JsonNode left, JsonNode right) {
    return left.equals((a, b) -> a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, right);
  }

  private static void requireCanonical(JsonNode expected, JsonNode actual) {
    require(actual.isArray() && same(expected, actual), "预收款明细快照格式无效");
    for (int i = 0; i < actual.size(); i++) {
      var lineNo = actual.get(i).path("lineNo");
      require(lineNo.isIntegralNumber() && lineNo.canConvertToInt() && lineNo.asInt() == i + 1, "预收款明细序号已变化");
    }
  }
}
