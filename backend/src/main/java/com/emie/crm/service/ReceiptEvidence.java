package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Set;

/** A receipt owns its uploaded vouchers independently of any order's editable attachments. */
public final class ReceiptEvidence {
  private ReceiptEvidence() {}

  public static ArrayNode validate(AssetService assets, JsonNode value) {
    var result = JsonNodeFactory.instance.arrayNode();
    if (value.isMissingNode() || value.isNull()) return result;
    require(value.isArray() && value.size() <= 10, "收款凭证须为图片列表，最多上传10张");
    for (var image : value) {
      require(image.isObject() && image.size() == 2 && image.has("name") && image.has("url"), "收款凭证格式无效");
      require(image.path("name").isTextual() && !image.path("name").asText().isBlank()
          && image.path("name").asText().length() <= 255
          && image.path("name").asText().chars().noneMatch(Character::isISOControl), "收款凭证文件名须为不超过255字的文本");
      require(image.path("url").isTextual()
          && image.path("url").asText().matches("/api/assets/[a-f0-9]{64}"), "请通过上传图片添加收款凭证");
      var asset = assets.read(image.path("url").asText().substring(AssetService.PREFIX.length()));
      require(asset != null && Set.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(asset.contentType())
          && asset.content() != null && asset.content().length > 0 && asset.content().length <= 5 * 1024 * 1024,
          "收款凭证须为不超过5MB的PNG、JPEG、GIF或WebP图片");
      result.add(image.deepCopy());
    }
    return result;
  }
}
