package com.emie.crm.security;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeishuApprovalClient {
  private static final String BASE = "https://open.feishu.cn/open-apis";
  private static final String APPROVALS = "/approval/v4/approvals/";
  private static final String INSTANCES = "/approval/v4/instances";
  private static final URI FILE_UPLOAD = URI.create("https://www.feishu.cn/approval/openapi/v2/file/upload");
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
  private static final Set<Integer> CREATE_REJECTIONS =
      Set.of(60001, 60002, 60004, 60005, 60006, 60009, 60011, 60013, 99991672, 99991673);

  private final FeishuClient identityClient;
  private final ObjectMapper mapper;
  private final String appSecret;
  private final String approvalCode;
  private final String prepaymentApprovalCode;
  private final String receiptApprovalCode;
  private final HttpClient http;
  // Names are presentation data. Cache successes and misses without persisting contacts.
  private final Map<String, CachedName> names = new LinkedHashMap<>();
  private TenantToken token;

  @Autowired
  public FeishuApprovalClient(FeishuClient identityClient, ObjectMapper mapper,
      @Value("${CRM_FEISHU_APP_SECRET:}") String appSecret,
      @Value("${CRM_FEISHU_SALES_APPROVAL_CODE:}") String approvalCode,
      @Value("${CRM_FEISHU_PREPAYMENT_APPROVAL_CODE:}") String prepaymentApprovalCode,
      @Value("${CRM_FEISHU_RECEIPT_APPROVAL_CODE:}") String receiptApprovalCode) {
    this(identityClient, mapper, appSecret, approvalCode, prepaymentApprovalCode, receiptApprovalCode,
        HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
  }

  FeishuApprovalClient(FeishuClient identityClient, ObjectMapper mapper, String appSecret,
      String approvalCode, String prepaymentApprovalCode, HttpClient http) {
    this(identityClient, mapper, appSecret, approvalCode, prepaymentApprovalCode, "", http);
  }

  FeishuApprovalClient(FeishuClient identityClient, ObjectMapper mapper, String appSecret,
      String approvalCode, String prepaymentApprovalCode, String receiptApprovalCode, HttpClient http) {
    this.identityClient = identityClient;
    this.mapper = mapper;
    this.appSecret = appSecret;
    this.approvalCode = approvalCode.strip();
    this.prepaymentApprovalCode = prepaymentApprovalCode.strip();
    this.receiptApprovalCode = receiptApprovalCode.strip();
    this.http = http;
    if (!this.approvalCode.isEmpty() && !ID.matcher(this.approvalCode).matches())
      throw new IllegalArgumentException("飞书销售订单审批模板编号无效");
    if (!this.prepaymentApprovalCode.isEmpty() && !ID.matcher(this.prepaymentApprovalCode).matches())
      throw new IllegalArgumentException("飞书预收款审批模板编号无效");
    if (!this.receiptApprovalCode.isEmpty() && !ID.matcher(this.receiptApprovalCode).matches())
      throw new IllegalArgumentException("飞书收款审批模板编号无效");
  }

  public boolean enabled() { return !approvalCode.isEmpty() || !prepaymentApprovalCode.isEmpty(); }
  public boolean enabled(String orderType) { return !approvalCode(orderType).isEmpty(); }
  public boolean identityReady() { return identityClient.ready(); }
  public boolean ready() { return enabled() && identityReady(); }
  public boolean ready(String orderType) { return enabled(orderType) && identityReady(); }
  public boolean receiptEnabled() { return !receiptApprovalCode.isEmpty(); }
  public boolean receiptReady() { return receiptEnabled() && identityReady(); }
  public String receiptApprovalCode() { return receiptApprovalCode; }
  public String approvalCode() { return approvalCode; }
  public String approvalCode(String orderType) {
    return switch (orderType) {
      case "销售订单", "海外提货单" -> approvalCode;
      case "预收款订单" -> prepaymentApprovalCode;
      default -> "";
    };
  }
  public String appId() { return identityClient.appId(); }

  /** Uploads one trusted CRM image; the returned file code is used in an approval form's value array. */
  public String uploadFile(String fileName, String type, String contentType, byte[] content) {
    requireReady();
    if (!Set.of("image", "attachment").contains(type == null ? "" : type))
      throw new ApiException("飞书凭证控件类型无效");
    if (content == null || content.length == 0 || content.length > MAX_IMAGE_BYTES)
      throw new ApiException("凭证图片须大于零且不能超过5MB");
    if (fileName == null || fileName.isBlank() || fileName.length() > 200
        || !fileName.equals(fileName.strip()) || fileName.chars().anyMatch(Character::isISOControl)
        || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('"') >= 0)
      throw new ApiException("凭证图片文件名无效");
    byte[] bytes = content.clone();
    String extension = imageExtension(contentType, bytes);
    String lowerName = fileName.toLowerCase(java.util.Locale.ROOT);
    if (!(lowerName.endsWith("." + extension) || extension.equals("jpeg") && lowerName.endsWith(".jpg")))
      throw new ApiException("凭证图片扩展名与内容格式不匹配");
    String boundary = "crm-approval-" + UUID.randomUUID().toString().replace("-", "");
    String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + fileName
        + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\n" + type
        + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"content\"; filename=\"" + fileName
        + "\"\r\nContent-Type: " + contentType + "\r\n\r\n";
    var request = HttpRequest.newBuilder(FILE_UPLOAD).timeout(UPLOAD_TIMEOUT)
        .header("Authorization", "Bearer " + tenantToken())
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(prefix.getBytes(StandardCharsets.UTF_8)),
            HttpRequest.BodyPublishers.ofByteArray(bytes),
            HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII))))
        .build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      var json = mapper.readTree(response.body());
      var code = json == null ? null : json.path("data").path("code");
      if (response.statusCode() == 200 && json != null && json.isObject()
          && json.path("code").isIntegralNumber() && json.path("code").canConvertToInt() && json.path("code").intValue() == 0
          && code != null && code.isTextual() && ID.matcher(code.asText()).matches()) return code.asText();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (IOException error) {
      // No response bodies, file bytes, tokens or temporary URLs enter logs or caller-facing errors.
    }
    throw new ApiException(502, "飞书凭证上传未完成，尚未发起本次审批，请重试");
  }

  private static String imageExtension(String contentType, byte[] bytes) {
    if ("image/png".equals(contentType) && bytes.length >= 8
        && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "png";
    if ("image/jpeg".equals(contentType) && bytes.length >= 3
        && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "jpeg";
    if ("image/gif".equals(contentType) && bytes.length >= 6
        && (new String(bytes, 0, 6, StandardCharsets.US_ASCII).equals("GIF89a")
            || new String(bytes, 0, 6, StandardCharsets.US_ASCII).equals("GIF87a"))) return "gif";
    if ("image/webp".equals(contentType) && bytes.length >= 12
        && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "webp";
    throw new ApiException("凭证仅支持内容格式匹配的PNG、JPEG、GIF和WebP图片");
  }

  public JsonNode definition() {
    return definition(approvalCode);
  }

  public JsonNode definition(String approvalCode) {
    requireReady();
    requireCode(approvalCode);
    var data = read(APPROVALS + approvalCode, "读取飞书审批模板");
    var returnedCode = data.path("approval_code");
    if (!"ACTIVE".equals(data.path("status").asText())
        || (!returnedCode.isMissingNode() && !approvalCode.equalsIgnoreCase(returnedCode.asText())))
      throw new ApiException(503, "飞书审批模板未启用或编号不匹配");
    return data;
  }

  public String create(String openId, String uuid, ArrayNode form) {
    return create(approvalCode, openId, uuid, form);
  }

  public String create(String approvalCode, String openId, String uuid, ArrayNode form) {
    requireReady();
    requireCode(approvalCode);
    if (openId == null || !ID.matcher(openId).matches() || uuid == null || uuid.length() > 64
        || !ID.matcher(uuid).matches() || form == null)
      throw new ApiException("飞书审批发起人或幂等编号无效");
    String bearer = tenantToken();
    Response response;
    try {
      response = request(INSTANCES, bearer, Map.of(
          "approval_code", approvalCode, "open_id", openId, "uuid", uuid,
          "form", mapper.writeValueAsString(form)));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Uncertain();
    } catch (IOException error) {
      throw new Uncertain();
    }
    var instanceCode = response.data().path("instance_code").asText();
    if (response.success() && ID.matcher(instanceCode).matches()) return instanceCode;
    if (response.code() == 60012) {
      try {
        var existing = instance(uuid);
        var recovered = existing.path("instance_code").asText();
        if (uuid.equalsIgnoreCase(existing.path("uuid").asText())
            && approvalCode.equalsIgnoreCase(existing.path("approval_code").asText())
            && openId.equals(existing.path("open_id").asText())
            && ID.matcher(recovered).matches()) return recovered;
      } catch (ApiException ignored) {
        // Keep the same UUID available for reconciliation when Feishu recovers.
      }
    }
    if (response.httpStatus() < 500 && CREATE_REJECTIONS.contains(response.code()))
      throw new Rejected(response.code());
    throw new Uncertain();
  }

  public JsonNode instance(String instanceCodeOrUuid) {
    requireReady();
    if (!ID.matcher(instanceCodeOrUuid).matches()) throw new ApiException("飞书审批实例编号无效");
    return read(INSTANCES + "/" + instanceCodeOrUuid, "查询飞书审批");
  }

  public JsonNode previewAfter(String instanceCode, String taskId, String approverOpenId) {
    requireReady();
    if (!ID.matcher(instanceCode).matches() || !ID.matcher(taskId).matches()
        || !ID.matcher(approverOpenId).matches())
      throw new ApiException("飞书审批预览所需的记录无效");
    var response = exchange(INSTANCES + "/preview?user_id_type=open_id", tenantToken(), Map.of(
        "instance_code", instanceCode, "task_id", taskId, "user_id", approverOpenId));
    var nodes = response.data().path("preview_nodes");
    if (!response.success() || !nodes.isArray())
      throw new ApiException(502, "无法预览飞书审批后续节点");
    return nodes;
  }

  /** Resolve approvers who have never logged into CRM; failure must not block approval sync. */
  public synchronized Map<String, String> userNames(Set<String> openIds) {
    var result = new HashMap<String, String>();
    if (!identityReady() || openIds == null || openIds.isEmpty()) return result;
    Instant now = Instant.now();
    var missing = new LinkedHashSet<String>();
    for (String openId : openIds) {
      if (openId == null || !ID.matcher(openId).matches()) continue;
      var cached = names.get(openId);
      if (cached == null || !now.isBefore(cached.expiresAt())) {
        // One batch is sufficient for normal flows and bounds network work for unusual data.
        if (missing.size() < 50) missing.add(openId);
      } else if (!cached.value().isEmpty()) {
        result.put(openId, cached.value());
      }
    }
    if (missing.isEmpty()) return result;
    var fetched = new HashMap<String, String>();
    try {
      var path = new StringBuilder("/contact/v3/users/batch?user_id_type=open_id");
      for (String id : missing) path.append("&user_ids=").append(id);
      var response = exchange(path.toString(), tenantToken(), null);
      if (response.success()) for (var user : response.data().path("items")) {
        String id = user.path("open_id").asText();
        String name = user.path("name").asText("").strip();
        if (missing.contains(id) && !name.isEmpty()) fetched.put(id, name);
      }
    } catch (ApiException ignored) {
      // Missing contacts permission or scope leaves the caller's local-name fallback intact.
    }
    for (String id : missing) {
      String name = fetched.getOrDefault(id, "");
      if (names.size() >= 1000 && !names.containsKey(id)) names.remove(names.keySet().iterator().next());
      names.put(id, new CachedName(name, now.plusSeconds(name.isEmpty() ? 60 : 600)));
    }
    result.putAll(fetched);
    return result;
  }

  public void cancel(String approvalCode, String instanceCode, String openId) {
    requireReady();
    if (!ID.matcher(approvalCode).matches() || !ID.matcher(instanceCode).matches() || openId.isBlank())
      throw new ApiException("飞书审批撤回所需的记录无效");
    var response = exchange(INSTANCES + "/cancel?user_id_type=open_id", tenantToken(), Map.of(
        "approval_code", approvalCode, "instance_code", instanceCode, "user_id", openId));
    if (response.success()) return;
    if (response.httpStatus() < 500 && (response.code() == 99991672 || response.code() == 60009))
      throw new WithdrawalDenied();
    throw new ApiException(502, "飞书未确认撤回审批（" + response.code() + "），请核对审批状态和模板撤回设置");
  }

  private JsonNode read(String path, String operation) {
    var response = exchange(path, tenantToken(), null);
    if (response.code() == 60003) throw new NotFound();
    if (response.code() == 99991672 || response.code() == 60009)
      throw new ApiException(503, "飞书应用尚未获得审批接口权限");
    if (!response.success() || !response.data().isObject())
      throw new ApiException(502, operation + "失败（" + response.code() + "）");
    return response.data();
  }

  private void requireReady() {
    if (!identityReady()) throw new ApiException(503, "飞书审批应用尚未配置完成");
  }

  private void requireCode(String code) {
    if (code == null || !ID.matcher(code).matches()) throw new ApiException(503, "飞书订单审批模板尚未配置完成");
  }

  private synchronized String tenantToken() {
    Instant now = Instant.now();
    if (token != null && now.isBefore(token.expiresAt())) return token.value();
    var response = exchange("/auth/v3/tenant_access_token/internal", null,
        Map.of("app_id", appId(), "app_secret", appSecret));
    String value = response.body().path("tenant_access_token").asText();
    long expires = response.body().path("expire").asLong(0);
    if (!response.success() || value.isBlank() || expires <= 0)
      throw new ApiException(502, "无法取得飞书审批应用的授权");
    token = new TenantToken(value, now.plusSeconds(Math.max(0, Math.min(expires, 7200) - 60)));
    return value;
  }

  private Response exchange(String path, String bearer, Object body) {
    try {
      return request(path, bearer, body);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (IOException error) {
      // The exception may contain upstream response details that should not be returned.
    }
    throw new ApiException(502, "飞书审批连接暂不可用，请稍后重试");
  }

  private Response request(String path, String bearer, Object body)
      throws IOException, InterruptedException {
    var builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(TIMEOUT);
    if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
    if (body != null) builder.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    var json = mapper.readTree(result.body());
    if (json == null || !json.isObject()) throw new IOException("Invalid Feishu response");
    return new Response(result.statusCode(), json);
  }

  private record TenantToken(String value, Instant expiresAt) {}
  private record CachedName(String value, Instant expiresAt) {}

  private record Response(int httpStatus, JsonNode body) {
    int code() { return body.path("code").asInt(-1); }
    JsonNode data() { return body.path("data"); }
    boolean success() { return httpStatus == 200 && code() == 0; }
  }

  public static final class Rejected extends ApiException {
    private Rejected(int code) { super("飞书拒绝发起审批（" + code + "），请核对模板和提交信息"); }
  }

  public static final class NotFound extends ApiException {
    private NotFound() { super(404, "飞书尚未返回该审批实例"); }
  }

  public static final class Uncertain extends ApiException {
    private Uncertain() { super(502, "飞书审批提交结果待核对，请同步状态"); }
  }

  public static final class WithdrawalDenied extends ApiException {
    private WithdrawalDenied() { super(503, "飞书应用尚未获得撤回审批权限"); }
  }
}
