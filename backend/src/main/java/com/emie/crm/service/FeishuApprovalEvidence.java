package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.function.Consumer;

/** Frozen private evidence preparation, completed before an approval instance can be created. */
final class FeishuApprovalEvidence {
  static final String FIELD = "evidenceUpload";
  private static final Set<String> NAMES = Set.of("收款凭证", "收款单", "付款凭证", "收款凭证图片", "附件", "图片附件", "图片");
  private static final Set<String> TYPES = Set.of("image", "imageV2", "attachmentV2");

  private FeishuApprovalEvidence() {}

  static boolean widget(JsonNode value) {
    return NAMES.contains(value.path("name").asText().strip()) && TYPES.contains(value.path("type").asText());
  }

  static ObjectNode plan(ObjectMapper mapper, JsonNode definition, JsonNode attachments) {
    boolean hasImages = attachments.isArray() && !attachments.isEmpty();
    require(attachments.isMissingNode() || attachments.isNull() || attachments.isArray(), "收款凭证格式无效");
    require(!attachments.isArray() || attachments.size() <= 10, "收款凭证最多上传10张图片");
    JsonNode widgets = definition.path("form");
    if (widgets.isTextual()) {
      try { widgets = mapper.readTree(widgets.textValue()); }
      catch (Exception invalid) { throw new ApiException(422, "飞书凭证字段格式无效"); }
    }
    require(widgets != null && widgets.isArray(), "飞书凭证字段格式无效");
    JsonNode chosen = null;
    for (var candidate : widgets) if (widget(candidate)) {
      require(chosen == null, "飞书模板存在多个凭证字段，请管理员保留一个收款凭证字段");
      chosen = candidate;
    }
    if (chosen == null) {
      require(!hasImages, "飞书模板尚无图片或附件字段，收款凭证已保存在CRM，请管理员添加后再提交审批");
      return null;
    }
    require(!chosen.path("required").asBoolean() || hasImages, "请上传收款凭证图片后再提交审批");
    if (!hasImages) return null;
    String id = chosen.path("id").asText();
    require(id.matches("[A-Za-z0-9_-]{1,128}"), "飞书凭证字段编号无效");
    for (var candidate : widgets)
      if (candidate != chosen) require(!id.equals(candidate.path("id").asText()), "飞书凭证字段编号重复");
    var result = mapper.createObjectNode().put("version", 1).put("widgetId", id)
        .put("widgetType", chosen.path("type").asText()).put("ready", false).put("createAttempted", false);
    result.set("assets", attachments.deepCopy()); result.putArray("codes");
    return result;
  }

  static void append(ObjectMapper mapper, JsonNode definition, JsonNode attachments, ArrayNode form) {
    var plan = plan(mapper, definition, attachments);
    if (plan != null) form.addObject().put("id", plan.path("widgetId").asText())
        .put("type", plan.path("widgetType").asText()).putArray("value");
  }

  static boolean retryable(JsonNode record) {
    return "FAILED".equals(record.path("status").asText()) && record.path("form").isArray()
        && record.path(FIELD).path("preparationFailed").asBoolean()
        && !record.path(FIELD).path("createAttempted").asBoolean();
  }

  static boolean attempted(JsonNode record) { return record.path(FIELD).path("createAttempted").asBoolean(); }

  static void prepare(ObjectNode record, AssetService assets, FeishuApprovalClient client, Consumer<ObjectNode> checkpoint) {
    if (!record.has(FIELD)) return;
    var plan = record.path(FIELD);
    require(plan.isObject() && plan.path("version").asInt() == 1 && TYPES.contains(plan.path("widgetType").asText()), "收款凭证上传计划无效");
    JsonNode source = record.has("orderSnapshot") ? record.path("orderSnapshot").path("attachments") : record.path("attachments");
    require(source.isArray() && !source.isEmpty() && source.equals(plan.path("assets")), "收款凭证与原申请快照不一致，请核对原申请");
    if (attempted(record)) { ready(record); checkpoint.accept(record); return; }
    var originals = ReceiptEvidence.validate(assets, plan.path("assets"));
    var mutable = (ObjectNode) plan;
    require(plan.path("codes").isArray(), "收款凭证文件编号格式无效");
    var codes = (ArrayNode) plan.path("codes");
    require(codes.size() <= originals.size(), "收款凭证文件编号数量不匹配");
    for (int i = 0; i < originals.size(); i++) {
      if (i < codes.size() && validCode(codes.get(i))) continue;
      checkpoint.accept(record); // Renew/check the claim before every external upload.
      String url = originals.get(i).path("url").asText();
      var asset = assets.read(url.substring(AssetService.PREFIX.length()));
      String extension = switch (asset.contentType()) {
        case "image/png" -> "png"; case "image/jpeg" -> "jpg";
        case "image/gif" -> "gif"; case "image/webp" -> "webp";
        default -> throw new ApiException("收款凭证仅支持图片");
      };
      String code = client.uploadFile("receipt-voucher-" + (i + 1) + "." + extension,
          "attachmentV2".equals(plan.path("widgetType").asText()) ? "attachment" : "image", asset.contentType(), asset.content());
      require(code != null && code.matches("[A-Za-z0-9_-]{1,128}"), "飞书凭证上传未返回有效文件编号");
      if (codes.size() == i) codes.add(code); else codes.set(i, record.textNode(code));
      checkpoint.accept(record); // A crash can orphan a file, but can never create a second approval.
    }
    field(record).set("value", codes.deepCopy());
    mutable.put("ready", true).remove("preparationFailed");
    checkpoint.accept(record);
  }

  static void beforeCreate(ObjectNode record, Consumer<ObjectNode> checkpoint) {
    if (!record.has(FIELD)) return;
    ready(record);
    ((ObjectNode) record.path(FIELD)).put("createAttempted", true).remove("preparationFailed");
    checkpoint.accept(record); // Persist the exact file codes before the native UUID is sent.
  }

  static void ready(JsonNode record) {
    var plan = record.path(FIELD); var codes = plan.path("codes");
    require(plan.path("ready").asBoolean() && codes.isArray() && !codes.isEmpty()
        && codes.size() == plan.path("assets").size(), "收款凭证尚未全部上传，不会发起缺少凭证的审批");
    for (var code : codes) require(validCode(code), "收款凭证文件编号无效");
    require(field(record).path("value").equals(codes), "收款凭证与冻结审批表单不一致，请核对原申请");
  }

  private static boolean validCode(JsonNode code) { return code != null && code.isTextual() && code.asText().matches("[A-Za-z0-9_-]{1,128}"); }

  private static ObjectNode field(JsonNode record) {
    require(record.path("form").isArray(), "原审批表单缺失，请核对原申请");
    ObjectNode found = null;
    for (var value : record.path("form")) if (record.path(FIELD).path("widgetId").asText().equals(value.path("id").asText())) {
      require(found == null && value.isObject() && record.path(FIELD).path("widgetType").asText().equals(value.path("type").asText()), "原审批凭证字段不匹配");
      found = (ObjectNode) value;
    }
    require(found != null, "原审批缺少凭证字段，请核对原申请");
    return found;
  }
}
