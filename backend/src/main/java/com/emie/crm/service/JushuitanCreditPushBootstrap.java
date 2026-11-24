package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** One specifically authorized historical order, disabled by default; never a historical-order scan. */
@Component
public final class JushuitanCreditPushBootstrap {
  record Authorization(String orderId, String approvalUuid, String instanceCode, String shopId,
      String authorizationId, BigDecimal expectedTotal, BigDecimal expectedPaid, String expectedOriginalAddress, JsonNode recipient) {
    Authorization(String orderId, String approvalUuid, String instanceCode, String shopId,
        String authorizationId, BigDecimal expectedTotal, BigDecimal expectedPaid) {
      this(orderId, approvalUuid, instanceCode, shopId, authorizationId, expectedTotal, expectedPaid, null, null);
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(JushuitanCreditPushBootstrap.class);
  private static final Set<String> FIELDS = Set.of("orderId", "approvalUuid", "instanceCode", "shopId",
      "authorizationId", "expectedTotal", "expectedPaid", "accountingMode");
  private static final Set<String> SHIPPING_FIELDS = Set.of("recipient", "expectedOriginalAddress");
  private final FeishuApprovalClient client;
  private final JushuitanCreditPushService queue;
  private final ObjectMapper mapper;
  private final String configuration;
  private final String accountingMode;
  private final AtomicBoolean started = new AtomicBoolean();

  public JushuitanCreditPushBootstrap(FeishuApprovalClient client, JushuitanCreditPushService queue,
      ObjectMapper mapper, @Value("${CRM_JST_CREDIT_BOOTSTRAP_JSON:}") String configuration,
      @Value("${CRM_JST_CREDIT_ACCOUNTING_MODE:}") String accountingMode) {
    this.client = client; this.queue = queue; this.mapper = mapper;
    this.configuration = configuration; this.accountingMode = accountingMode;
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    if (configuration.isBlank() || !started.compareAndSet(false, true)) return;
    try {
      require(JushuitanCreditPushPolicy.ACCOUNTING_MODE.equals(accountingMode), "账期挂账模式未启用，不能执行单次授权补推");
      var authorization = parse(mapper, configuration);
      require(client.identityReady(), "飞书身份配置未就绪，不能核验授权审批");
      // A fresh authenticated Feishu GET is mandatory even if CRM already records APPROVED.
      var instance = client.instance(authorization.instanceCode());
      var result = queue.bootstrap(authorization, instance);
      LOG.info("Authorized one-order credit bootstrap {} recorded with status {}", authorization.authorizationId(), result.path("status").asText());
    } catch (RuntimeException failure) {
      // Do not turn an optional bootstrap failure into application downtime or expose native PII.
      LOG.warn("Authorized one-order credit bootstrap was not recorded ({})", failure.getClass().getSimpleName());
    }
  }

  static Authorization parse(ObjectMapper mapper, String configuration) {
    try {
      require(configuration != null && !configuration.isBlank() && configuration.length() <= 4096, "单次授权配置无效");
      var strict = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      var value = strict.readTree(configuration);
      require(value.isObject() && (value.size() == FIELDS.size() || value.size() == FIELDS.size() + SHIPPING_FIELDS.size()), "单次授权必须是一个完整对象，不能提供列表");
      value.fieldNames().forEachRemaining(key -> require(FIELDS.contains(key) || SHIPPING_FIELDS.contains(key), "单次授权包含未知字段"));
      for (String field : FIELDS) require(value.has(field), "单次授权缺少必要字段");
      require(JushuitanCreditPushPolicy.ACCOUNTING_MODE.equals(value.path("accountingMode").asText()), "单次授权必须明确账期挂账（非实收）口径");
      String shop = JushuitanCreditPushPolicy.identifier(value.path("shopId"));
      require(!shop.isBlank(), "单次授权店铺编号无效");
      var total = money(value.path("expectedTotal")); var paid = money(value.path("expectedPaid"));
      require(total.signum() > 0 && paid.signum() >= 0 && paid.compareTo(total) <= 0, "单次授权核验金额无效");
      JsonNode recipient = null; String expectedAddress = null;
      if (value.has("recipient") || value.has("expectedOriginalAddress")) {
        recipient = shippingRecipient(value.path("recipient"));
        var original = value.path("expectedOriginalAddress");
        require(original.isTextual() && !original.asText().strip().isEmpty() && original.asText().strip().length() <= 200
            && original.asText().chars().noneMatch(Character::isISOControl), "收货补充授权须绑定原审批地址");
        expectedAddress = original.asText().strip();
      }
      return new Authorization(identity(value, "orderId"), identity(value, "approvalUuid"), identity(value, "instanceCode"),
          shop, identity(value, "authorizationId"), total, paid, expectedAddress, recipient);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalArgumentException("单次授权JSON无效");
    }
  }

  static JsonNode shippingRecipient(JsonNode value) {
    require(value.isObject() && value.size() == 4, "收货补充授权须同时提供省、市、区县及详细地址");
    for (String field : Set.of("province", "city", "district", "address"))
      require(value.path(field).isTextual(), "收货补充授权只能包含省、市、区县及详细地址文本");
    require(CreditShippingAddress.valid(value), CreditShippingAddress.MESSAGE);
    var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    for (String field : Set.of("province", "city", "district", "address")) result.put(field, value.path(field).asText().strip());
    return result;
  }

  private static String identity(JsonNode value, String field) {
    var node = value.path(field);
    require(node.isTextual() && node.asText().matches("[A-Za-z0-9_-]{1,191}"), "单次授权标识无效");
    return node.asText();
  }

  static BigDecimal money(JsonNode node) {
    require((node.isTextual() || node.isNumber()) && node.asText().matches("[0-9]{1,12}(\\.[0-9]{1,2})?"), "单次授权核验金额无效");
    return new BigDecimal(node.asText());
  }
}
