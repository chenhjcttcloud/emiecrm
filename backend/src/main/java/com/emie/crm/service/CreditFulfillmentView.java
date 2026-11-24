package com.emie.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Read-only warnings about fulfillment that continues independently of Feishu's decision. */
final class CreditFulfillmentView {
  private CreditFulfillmentView() {}

  static boolean operational(JsonNode shipment) {
    if (shipment == null || shipment.isMissingNode() || shipment.isNull()) return false;
    var id = shipment.path("oId");
    if (id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0) return true;
    if (Set.of("ACCEPTED", "PARTIAL", "SHIPPED", "EXCEPTION", "CANCELLED").contains(shipment.path("status").asText())) return true;
    return shipment.path("upstreamAttempted").asBoolean() && !shipment.path("confirmedRejected").asBoolean();
  }

  static boolean exception(JsonNode order, JsonNode shipment) {
    return exception(order, shipment, null);
  }

  static boolean exception(JsonNode order, JsonNode shipment, JsonNode approval) {
    if (order == null || !operational(shipment)) return false;
    if (order.hasNonNull("deletedAt") || Set.of("REJECTED", "CANCELED", "CANCELLED", "FAILED", "DELETED", "UNKNOWN").contains(approvalStatus(order, approval))) return true;
    String status = order.path("status").asText();
    if ("已批准".equals(status)) return false;
    if ("审批中".equals(status)) return Set.of("SHIPPED", "PARTIAL").contains(shipment.path("status").asText())
        || "Sent".equals(shipment.path("nativeStatus").asText());
    return true;
  }

  static String approvalStatus(JsonNode order, JsonNode approval) {
    return approval == null || approval.path("status").asText().isBlank()
        ? order.path("status").asText() : approval.path("status").asText();
  }

  static String message(JsonNode order, JsonNode shipment, JsonNode approval) {
    if (!exception(order, shipment, approval)) return "";
    String decision = switch (approvalStatus(order, approval)) {
      case "REJECTED" -> "飞书审批已驳回";
      case "CANCELED", "CANCELLED", "已取消" -> "飞书审批已撤回或取消";
      case "FAILED" -> "飞书审批创建失败";
      case "PENDING", "QUEUED", "SUBMITTING", "审批中" -> "飞书审批并行处理中，聚水潭已发货或部分发货，待核对";
      case "UNKNOWN" -> "飞书审批结果尚未确认";
      default -> "CRM订单尚未批准";
    };
    return decision + "，聚水潭原单仍有执行记录；请财务和仓库核对原单，审批结果不会自动撤销ERP发货或生成收款";
  }

  static Map<String, Object> fields(JsonNode order, JsonNode shipment, JsonNode approval) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("approvalStatus", approvalStatus(order, approval));
    fields.put("crmStatus", order.path("status").asText());
    fields.put("erpStatus", shipment == null ? "" : shipment.path("status").asText());
    fields.put("nativeStatus", shipment == null ? "" : shipment.path("nativeStatus").asText());
    fields.put("fulfillmentException", exception(order, shipment, approval));
    fields.put("fulfillmentWarning", message(order, shipment, approval));
    return fields;
  }
}
