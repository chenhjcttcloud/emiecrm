package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

/** Historical customer provenance and opening credit are distinct from receipts and ERP identities. */
final class LegacyCustomerData {
  static final String SYSTEM = "legacy-feishu-crm";
  static final Set<String> PROTECTED_FIELDS = Set.of("openingBalance", "legacySource");
  private LegacyCustomerData() {}

  static BigDecimal amount(JsonNode value) {
    require(value.isNumber(), "期初余额必须为数字");
    BigDecimal amount = value.decimalValue();
    require(amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(new BigDecimal("999999999999.99")) <= 0,
        "期初余额须为非负金额，最多两位小数");
    return amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  static BigDecimal opening(JsonNode customer) {
    return customer.has("openingBalance") ? amount(customer.path("openingBalance")) : BigDecimal.ZERO;
  }

  static void validate(JsonNode customers) {
    var ids = new HashSet<String>();
    for (JsonNode customer : customers) {
      if (customer.has("openingBalance")) {
        amount(customer.path("openingBalance"));
        require(customer.has("legacySource"), "期初余额缺少旧系统来源");
      }
      if (!customer.has("legacySource")) continue;
      JsonNode source = customer.path("legacySource");
      require(source.isObject() && SYSTEM.equals(source.path("system").asText()), "旧客户来源系统无效");
      text(source, "id", 100, true);
      require(ids.add(source.path("id").asText()), "旧系统客户 ID 不能重复关联");
      require(source.path("sha256").isTextual() && source.path("sha256").asText().matches("[a-f0-9]{64}"),
          "旧客户来源文件校验值无效");
      text(source, "fileName", 255, true); text(source, "sheet", 200, true);
      require(source.path("rowNumber").isIntegralNumber() && source.path("rowNumber").canConvertToInt()
          && source.path("rowNumber").asInt() >= 2 && source.path("rowNumber").asInt() <= 1_000_000,
          "旧客户来源行号无效");
      if (source.has("exportedAt")) text(source, "exportedAt", 100, false);
      text(source, "importedAt", 100, true);
      raw(source.path("fields"));
      basis(source.path("fields"), source.path("id").asText(), customer.path("openingBalance"));
    }
  }

  static void raw(JsonNode fields) {
    require(fields.isObject() && !fields.isEmpty() && fields.size() <= 50, "旧客户原始字段无效");
    for (var field : fields.properties())
      require(!field.getKey().isBlank() && field.getKey().length() <= 200
          && (field.getValue().isNull() || (field.getValue().isTextual() && field.getValue().asText().length() <= 10_000)),
          "旧客户原始字段必须保留为文本或空值");
  }

  static void basis(JsonNode fields, String id, JsonNode opening) {
    require(fields.path("ID").isTextual() && id.equals(fields.path("ID").asText()), "旧客户ID与原始依据不一致");
    JsonNode rawAmount = fields.path("预收款金额");
    boolean blank = rawAmount.isMissingNode() || rawAmount.isNull() || rawAmount.asText().isBlank();
    boolean present = !opening.isMissingNode() && !opening.isNull();
    require(blank != present, "旧客户原始余额空白与期初余额不一致");
    if (blank) return;
    String text = rawAmount.asText().trim();
    require(text.matches("[0-9]+(?:\\.[0-9]+)?") && text.length() <= 40, "旧客户原始余额格式无效");
    BigDecimal original = new BigDecimal(text);
    require(amount(opening).compareTo(original) == 0, "期初余额与旧客户原始金额不一致");
  }

  static void text(JsonNode node, String key, int maximum, boolean required) {
    JsonNode value = node.path(key);
    require(value.isTextual() && value.asText().length() <= maximum
        && (!required || !value.asText().isBlank())
        && value.asText().codePoints().noneMatch(Character::isISOControl), "旧客户来源字段无效：" + key);
  }
}
