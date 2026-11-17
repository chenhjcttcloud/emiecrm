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
  @Value("${CRM_JST_READ_ONLY:false}") private boolean readOnly;
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

  public boolean changesEnabled() { return featureEnabled("order_change_enabled"); }
  public boolean uploadsEnabled() { return featureEnabled("order_upload_enabled"); }

  public Map<String, Object> authorizationStatus() {
    if (!configured()) return Map.of("status", "UNAVAILABLE");
    try {
      URI uri = URI.create(url + "/ready");
      if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null) return Map.of("status", "UNAVAILABLE");
      var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
          .header("Authorization", "Bearer " + token).GET().build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      var info = mapper.readTree(response.body());
      if (response.statusCode() != 200 || !info.path("environment").asText().equals(environment))
        return Map.of("status", "UNAVAILABLE");
      var auth = info.path("authorization");
      // Whitelist public metadata: never relay gateway credentials or upstream error text.
      return Map.of("status", auth.path("status").asText("NOT_IMPLEMENTED"),
          "enabled", auth.path("enabled").asBoolean(), "expiresAt", auth.path("expiresAt").asLong(),
          "lastSuccessAt", auth.path("lastSuccessAt").asLong());
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return Map.of("status", "UNAVAILABLE"); }
    catch (Exception e) { return Map.of("status", "UNAVAILABLE"); }
  }

  private boolean featureEnabled(String flag) {
    if (!configured() || readOnly) return false;
    try {
      URI uri = URI.create(url + "/ready");
      if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null) return false;
      var req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
          .header("Authorization", "Bearer " + token).GET().build();
      var response = http.send(req, HttpResponse.BodyHandlers.ofString());
      var info = mapper.readTree(response.body());
      return response.statusCode() == 200 && info.path("environment").asText().equals(environment)
          && info.path(flag).asBoolean();
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    catch (Exception e) { return false; }
  }

  public JsonNode mutate(String id, String path, JsonNode biz) {
    return request("/v1/mutations", Map.of("id", id, "path", path, "biz", biz));
  }

  private JsonNode request(String path, Object body) {
    if (!configured()) throw new ApiException(503, "尚未配置聚水潭云端对接服务");
    boolean query = path.equals("/v1/query");
    if (!query && readOnly) throw new ApiException(503, "当前CRM仅允许聚水潭只读查询，不能上传或变更订单");
    try {
      URI uri = URI.create(url + path);
      if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null)
        throw new ApiException(503, "聚水潭网关须使用 HTTPS 地址");
      var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35))
          .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
          .header("X-CRM-JST-Environment", environment)
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        if (query && response.statusCode() == 409)
          throw new ApiException(503, "CRM与聚水潭网关环境不一致，请联系管理员核对正式/沙箱配置");
        if (query && response.statusCode() == 401)
          throw new ApiException(503, "聚水潭网关认证失败，请联系管理员检查连接配置");
        if (query && response.statusCode() == 403)
          throw new ApiException(503, "聚水潭查询接口未获网关放行，请联系管理员检查接口白名单");
        throw new ApiException(502, query ? "聚水潭查询服务暂不可用，请稍后重试" : "云端接口暂不可用，请先同步核对订单后重试");
      }
      var result = mapper.readTree(response.body());
      if (!result.has("code")) throw new ApiException(502, query ? "聚水潭查询结果格式异常，请稍后重试" : "云端接口返回格式异常，请先同步核对订单");
      return result;
    } catch (ApiException e) { throw e; }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(502, query ? "聚水潭查询已中断，请重试" : "调用中断，请同步核对聚水潭订单");
    } catch (Exception e) { throw new ApiException(502, query ? "聚水潭查询连接失败，请检查网络后重试" : "云端连接失败，请同步核对聚水潭订单"); }
  }
}
