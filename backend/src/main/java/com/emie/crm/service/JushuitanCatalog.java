package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Local read-only projection. Historical IDs remain usable for displaying old orders. */
public class JushuitanCatalog {
  public static ArrayNode merge(ObjectMapper mapper, JsonNode previous, JsonNode remote, String environment) {
    return merge(mapper, previous, remote, environment, null);
  }
  public static ArrayNode merge(ObjectMapper mapper, JsonNode previous, JsonNode remote, String environment, Set<String> refreshedSkus) {
    var result = mapper.createArrayNode();
    var ids = new HashMap<String, String>();
    for (var p : previous) if (p.path("source").asText().equals("jushuitan")
        && p.path("jstEnvironment").asText().equals(environment)) ids.put(p.path("jstSku").asText(), p.path("id").asText());
    var seen = new HashSet<String>();
    var refreshed = new HashSet<String>();
    for (var row : remote) {
      String sku = row.path("sku_id").asText("").trim();
      require(!sku.isBlank() && seen.add(sku), "聚水潭返回空或重复SKU，保留原目录，请联系管理员");
      String id = ids.getOrDefault(sku, "jst-" + environment + "-" + UUID.nameUUIDFromBytes(sku.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      refreshed.add(id);
      var p = result.addObject().put("id", id).put("source", "jushuitan").put("jstEnvironment", environment)
          .put("jstSku", sku).put("code", sku).put("name", row.path("name").asText(""))
          .put("model", row.path("properties_value").asText("")).put("unit", row.path("unit").asText(""))
          .put("category", row.path("category").asText("")).put("enabled", row.path("enabled").asInt() == 1)
          .put("jstImage", image(row)).put("syncedAt", com.emie.crm.repository.CrmRepository.now());
      for (var field : Map.of("price", "sale_price", "cost", "cost_price").entrySet())
        p.set(field.getKey(), row.hasNonNull(field.getValue()) ? row.path(field.getValue()) : TextNode.valueOf(""));
    }
    for (var old : previous) if (!refreshed.contains(old.path("id").asText())) {
      ObjectNode history = old.deepCopy();
      if (refreshedSkus == null || !old.path("source").asText().equals("jushuitan")
          || !old.path("jstEnvironment").asText().equals(environment) || refreshedSkus.contains(old.path("jstSku").asText()))
        history.put("enabled", false).put("historicalOnly", true);
      result.add(history);
    }
    return result;
  }
  // Vendor image links are distinct from CRM-uploaded /api/assets/ attachments.
  // Never send CRM credentials to a vendor CDN or fetch arbitrary URLs on the server.
  static String image(JsonNode row) {
    for (String field : List.of("pic_big", "pic")) {
      String value = row.path(field).asText("").trim();
      if (value.startsWith("//")) value = "https:" + value;
      if (value.startsWith("http://")) value = "https://" + value.substring(7);
      try {
        var uri = java.net.URI.create(value);
        if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return value;
      } catch (IllegalArgumentException ignored) { }
    }
    return "";
  }
}
