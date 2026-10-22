package com.emie.crm.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

public class JushuitanShipmentSummary {
  public static ArrayNode shipments(ObjectMapper mapper, JsonNode outs) {
    var result = mapper.createArrayNode();
    var seen = new HashSet<String>();
    for (var out : outs) {
      if (!Set.of("Confirmed", "Archive").contains(out.path("status").asText())) continue;
      String id = out.path("io_id").asText();
      if (id.isBlank() || !seen.add(id)) continue;
      var shipment = result.addObject().put("id", id).put("company", out.path("logistics_company").asText())
          .put("trackingNumber", out.path("l_id").asText()).put("sentAt", out.path("io_date").asText(out.path("modified").asText()));
      var items = shipment.putArray("items");
      for (var line : out.path("items")) items.addObject().put("skuId", line.path("sku_id").asText())
          .put("quantity", line.path("qty").decimalValue()).put("lineId", line.path("outer_oi_id").asText());
    }
    return result;
  }

  public static ArrayNode shipments(ObjectMapper mapper, JsonNode outs, JsonNode orders) {
    var result = shipments(mapper, outs);
    var warehouseOrders = new HashSet<String>();
    for (var out : outs) if (Set.of("Confirmed", "Archive").contains(out.path("status").asText()))
      warehouseOrders.add(out.path("o_id").asText());
    var seen = new HashSet<String>();
    for (var order : orders) {
      String id = order.path("o_id").asText();
      // This API marks external fulfillment on the order and deletes any pending WMS out.
      // Do not infer fulfillment from Sent alone: warehouse revocation can lag order state.
      if (id.isBlank() || !seen.add(id) || warehouseOrders.contains(id)
          || !"Sent".equals(order.path("status").asText())
          || !order.path("labels").asText().contains("开放平台发货")
          || order.path("l_id").asText("").isBlank() || order.path("send_date").asText("").isBlank()) continue;
      var shipment = result.addObject().put("id", "external-order-" + id)
          .put("company", order.path("logistics_company").asText())
          .put("trackingNumber", order.path("l_id").asText()).put("sentAt", order.path("send_date").asText());
      var items = shipment.putArray("items");
      for (var line : order.path("items")) items.addObject().put("skuId", line.path("sku_id").asText())
          .put("quantity", line.path("qty").decimalValue()).put("lineId", line.path("outer_oi_id").asText());
    }
    return result;
  }

  public static String status(JsonNode orders, JsonNode shipments, JsonNode expected) {
    var states = new ArrayList<String>();
    orders.forEach(o -> states.add(o.path("status").asText()));
    if (!states.isEmpty() && states.stream().allMatch("Cancelled"::equals)) return "CANCELLED";
    Map<String, BigDecimal> wanted = new HashMap<>(), sent = new HashMap<>();
    for (var line : expected) wanted.merge(line.path("sku_id").asText(), line.path("qty").decimalValue(), BigDecimal::add);
    for (var shipment : shipments) for (var line : shipment.path("items"))
      sent.merge(line.path("skuId").asText(), line.path("quantity").decimalValue(), BigDecimal::add);
    if (!wanted.isEmpty() && wanted.entrySet().stream().allMatch(e -> sent.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) >= 0)) return "SHIPPED";
    if (!shipments.isEmpty() || states.contains("Sent")) return "PARTIAL";
    if (states.contains("Question")) return "EXCEPTION";
    return "ACCEPTED";
  }
}
