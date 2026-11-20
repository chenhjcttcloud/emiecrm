package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** External customer identities are scoped; they never replace CRM customer IDs. */
public final class CustomerExternalLinks {
  private CustomerExternalLinks() {}
  public static final Set<String> ENVIRONMENTS = Set.of("production", "sandbox");
  public static final Set<String> TYPES = Set.of("offline", "distributor", "buyer");

  public static String key(String environment, String type, String id) {
    return environment + "\n" + type + "\n" + id;
  }

  public static void validate(JsonNode customers) {
    var identities = new HashSet<String>();
    for (var customer : customers) {
      if (!customer.has("externalLinks")) continue;
      var links = customer.path("externalLinks");
      require(links.isArray() && links.size() <= 20, "客户最多关联20个外部编号");
      for (var link : links) {
        require(link.isObject() && link.path("system").isTextual()
            && link.path("system").asText().equals("jushuitan"), "外部客户系统无效");
        String environment = link.path("environment").asText();
        String type = link.path("type").asText();
        var id = link.path("id");
        require(ENVIRONMENTS.contains(environment) && TYPES.contains(type), "聚水潭客户编号的环境或类型无效");
        require(id.isTextual() && !id.asText().isBlank() && id.asText().equals(id.asText().trim())
            && id.asText().length() <= 100 && id.asText().codePoints().noneMatch(Character::isISOControl),
            "聚水潭客户编号须为1至100个字符的文本，且不能包含控制字符或首尾空格");
        require(!link.has("name") || (link.path("name").isTextual() && link.path("name").asText().length() <= 200),
            "聚水潭客户名称最多200个字符");
        require(identities.add(key(environment, type, id.asText())), "同一环境和类型的聚水潭客户编号不能重复关联");
      }
    }
  }
}
