package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JushuitanClient {
  private final String url, token, environment;
  private final ObjectMapper mapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

  public JushuitanClient(ObjectMapper mapper,
      @Value("${CRM_JST_GATEWAY_URL:}") String url,
      @Value("${CRM_JST_GATEWAY_TOKEN:}") String token,
      @Value("${CRM_JST_ENV:sandbox}") String environment) {
    this.mapper = mapper;
    this.url = url.replaceAll("/+$", "");
    this.token = token;
    this.environment = environment;
  }

  public boolean configured() { return !url.isBlank() && !token.isBlank(); }

  public JsonNode query(String path, Object biz) {
    return request("/v1/query", Map.of("path", path, "biz", biz));
  }

  public JsonNode upload(JsonNode orders) { return request("/v1/orders", orders); }

  public boolean changesEnabled() {
    if (!configured()) return false;
    try {
      URI uri = URI.create(url + "/ready");
      if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null) return false;
      var req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
          .header("Authorization", "Bearer " + token).GET().build();
      var response = http.send(req, HttpResponse.BodyHandlers.ofString());
      var info = mapper.readTree(response.body());
      return response.statusCode() == 200 && info.path("environment").asText().equals(environment)
          && info.path("order_change_enabled").asBoolean();
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    catch (Exception e) { return false; }
  }

  public JsonNode mutate(String id, String path, JsonNode biz) {
    return request("/v1/mutations", Map.of("id", id, "path", path, "biz", biz));
  }

  private JsonNode request(String path, Object body) {
    if (!configured()) throw new ApiException(503, "尚未配置聚水潭云端对接服务");
    try {
      URI uri = URI.create(url + path);
      if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null)
        throw new ApiException(503, "聚水潭网关须使用 HTTPS 地址");
      var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35))
          .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
          .header("X-CRM-JST-Environment", environment)
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new ApiException(502, "云端接口暂不可用，请先同步核对订单后重试");
      var result = mapper.readTree(response.body());
      if (!result.has("code")) throw new ApiException(502, "云端接口返回格式异常，请先同步核对订单");
      return result;
    } catch (ApiException e) { throw e; }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(502, "调用中断，请同步核对聚水潭订单");
    } catch (Exception e) { throw new ApiException(502, "云端连接失败，请同步核对聚水潭订单"); }
  }
}
