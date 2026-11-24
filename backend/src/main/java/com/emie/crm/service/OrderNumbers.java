package com.emie.crm.service;

import com.fasterxml.jackson.databind.JsonNode;

/** A business number is presentation only; persisted links continue to use the internal order ID. */
public final class OrderNumbers {
  private OrderNumbers() {}

  public static String display(JsonNode order) {
    if (order == null) return "";
    String number = order.path("orderNumber").asText("").strip();
    return number.isEmpty() ? order.path("id").asText("") : number;
  }
}
