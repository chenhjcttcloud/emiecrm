package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/** External order identities are frozen once; existing ERP orders are never adopted by a new request. */
final class JushuitanOrderIdentity {
  private static final int MAX_QUERY_PAGES = 20;
  private static final String QUERY = "/open/orders/single/query";

  private JushuitanOrderIdentity() {}

  /** The caller supplies a persisted, authorized order whose business number has already been reserved. */
  static String create(JsonNode order) {
    String number = OrderNumbers.display(order);
    return isBusinessNumber(number) ? number : "CRM-" + UUID.randomUUID().toString().replace("-", "");
  }

  static boolean valid(String soId, JsonNode frozenOrder) {
    return legacy(soId) || isUnified(soId, frozenOrder);
  }

  static boolean isBusinessNumber(String value) { return OrderNumberService.isShortNumber(value); }

  static boolean isUnified(String soId, JsonNode order) {
    return isBusinessNumber(soId) && soId.equals(OrderNumbers.display(order));
  }

  private static boolean legacy(String soId) {
    return soId != null && soId.matches("CRM-[a-f0-9]{32}");
  }

  /**
   * Run immediately before every permitted upload of a unified number, including
   * a frozen request that was conclusively rejected. Unknown results keep their
   * query-only recovery path and never reach an upload preflight.
   */
  static void requireUnused(JushuitanClient client, ObjectMapper mapper, JsonNode request) {
    JsonNode identity = request == null ? null : request.path("so_id");
    if (identity == null || !identity.isTextual()) throw new ApiException("聚水潭订单编号无效");
    String soId = identity.asText();
    if (legacy(soId)) return;
    if (!isBusinessNumber(soId)) throw new ApiException("聚水潭订单编号无效");
    var shop = request.path("shop_id");
    if (!shop.isIntegralNumber() || !shop.canConvertToLong() || shop.longValue() <= 0)
      throw new ApiException("聚水潭订单店铺无效");
    var parameters = mapper.createObjectNode().put("shop_id", shop.longValue()).put("page_size", 50);
    parameters.putArray("so_ids").add(soId);
    for (int page = 1; page <= MAX_QUERY_PAGES; page++) {
      parameters.put("page_index", page);
      final JsonNode response;
      try { response = client.query(QUERY, parameters.deepCopy()); }
      catch (ApiException failure) { throw failure; }
      catch (RuntimeException failure) { throw incomplete(); }
      if (response == null || !response.path("code").isIntegralNumber() || !response.path("code").canConvertToInt()
          || response.path("code").intValue() != 0 || !response.path("data").isObject()) throw incomplete();
      var data = response.path("data");
      if (!data.path("orders").isArray() || !data.path("has_next").isBoolean()) throw incomplete();
      for (var order : data.path("orders")) {
        if (!order.isObject() || !order.path("so_id").isTextual()) throw incomplete();
        // A matching foreign or incomplete record is still a collision, never an invitation to adopt it.
        if (soId.equals(order.path("so_id").asText()))
          throw new ApiException(409, "聚水潭已存在该订单号，请核对原单，不能自动覆盖或重复创建");
        // An exact-identity query returning another identity cannot prove that this number is unused.
        throw incomplete();
      }
      if (!data.path("has_next").booleanValue()) return;
    }
    throw incomplete();
  }

  private static ApiException incomplete() {
    return new ApiException(502, "尚未完整核对聚水潭订单编号，请稍后重试；本次不会上传订单");
  }
}
