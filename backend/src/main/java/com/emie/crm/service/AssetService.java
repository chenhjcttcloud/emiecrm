package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Image bytes live separately from the business document, in the existing backed-up database. */
@Service
public class AssetService {
  public static final String PREFIX = "/api/assets/";
  private static final int MAX = 5 * 1024 * 1024;
  private final CrmRepository db;

  public AssetService(CrmRepository db) { this.db = db; }

  public record Asset(String contentType, byte[] content) {}

  private String imageType(byte[] bytes) {
    require(bytes.length > 0 && bytes.length <= MAX, "图片须大于零且不能超过5MB");
    if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
    if (bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "image/jpeg";
    if (bytes.length >= 6 && (new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a")
        || new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF87a"))) return "image/gif";
    if (bytes.length >= 12 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
    throw new com.emie.crm.common.exception.ApiException("仅支持PNG、JPEG、GIF和WebP图片");
  }

  @Transactional
  public String store(byte[] content) {
    String type = imageType(content);
    try {
      String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      db.jdbc().update("INSERT IGNORE INTO assets(id,content_type,content) VALUES(?,?,?)", id, type, content);
      return PREFIX + id;
    } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  public Asset read(String id) {
    require(id.matches("[a-f0-9]{64}"), "图片地址无效");
    var rows = db.jdbc().queryForList("SELECT content_type,content FROM assets WHERE id=?", id);
    if (rows.isEmpty()) throw new com.emie.crm.common.exception.ApiException(404, "图片不存在");
    return new Asset(rows.getFirst().get("content_type").toString(), (byte[]) rows.getFirst().get("content"));
  }

  private void images(JsonNode state, java.util.function.BiConsumer<ObjectNode, String> action) {
    for (var product : state.path("products")) action.accept((ObjectNode) product, "image");
    for (var order : state.path("orders"))
      for (var attachment : order.path("attachments")) {
        require(attachment.isObject(), "附件格式无效");
        action.accept((ObjectNode) attachment, "url");
      }
  }

  private byte[] decode(String data) {
    try { return Base64.getDecoder().decode(data); }
    catch (IllegalArgumentException e) { throw new com.emie.crm.common.exception.ApiException("图片编码无效"); }
  }

  public void externalize(JsonNode state) {
    var referenced = new HashSet<String>();
    images(state, (node, key) -> {
      String url = node.path(key).asText("");
      if (url.startsWith("data:image/")) {
        int comma = url.indexOf(',');
        require(comma > 0 && url.substring(0, comma).endsWith(";base64"), "图片编码无效");
        node.put(key, store(decode(url.substring(comma + 1))));
      } else if (url.startsWith(PREFIX)) {
        String id = url.substring(PREFIX.length());
        require(id.matches("[a-f0-9]{64}"), "图片地址无效");
        referenced.add(id);
      } else require(url.isBlank(), "请通过上传图片添加附件");
    });
    // Validate references in batches instead of one round trip per image while holding the state lock.
    var ids = new ArrayList<>(referenced);
    for (int start = 0; start < ids.size(); start += 1000) {
      var batch = ids.subList(start, Math.min(start + 1000, ids.size()));
      String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
      Long count = db.jdbc().queryForObject("SELECT COUNT(*) FROM assets WHERE id IN (" + placeholders + ")", Long.class, batch.toArray());
      require(count != null && count == batch.size(), "图片不存在，请重新上传");
    }
  }

  public Map<String, Object> backup(JsonNode state) {
    var result = new LinkedHashMap<String, Object>();
    images(state, (node, key) -> {
      String url = node.path(key).asText("");
      if (url.startsWith(PREFIX) && !result.containsKey(url.substring(PREFIX.length()))) {
        String id = url.substring(PREFIX.length());
        var asset = read(id);
        result.put(id, Map.of("contentType", asset.contentType(), "data", Base64.getEncoder().encodeToString(asset.content())));
      }
    });
    return result;
  }

  public void restore(JsonNode assets) {
    require(assets.isMissingNode() || assets.isObject(), "附件备份格式无效");
    assets.fields().forEachRemaining(entry -> {
      String url = store(decode(entry.getValue().path("data").asText()));
      require(url.equals(PREFIX + entry.getKey()), "附件备份校验失败");
    });
  }
}
