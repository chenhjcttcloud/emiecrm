package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persisted intent only: approval recognition never makes an ERP request or fabricates a receipt. */
final class JushuitanCreditPushPolicy {
  static final int VERSION = 1;
  static final int SUBMISSION_VERSION = 2;
  static final String SUBMISSION_TRIGGER = "ORDER_SUBMISSION";
  static final String ACCOUNTING_MODE = "ON_ACCOUNT_TENDER";
  static final String FIELD = "creditPush";
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final List<String> APPROVED_FIELDS = List.of("id", "type", "customerId", "owner", "total", "items", "approvalDraft");
  private static final List<String> PRODUCT_FIELDS = List.of("id", "name", "model", "code", "jstSku", "source", "enabled", "jstEnvironment");
  private static final Map<String, String> RECIPIENT = Map.of("name", "recipientName", "phone", "recipientPhone", "address", "recipientAddress",
      "province", "recipientProvince", "city", "recipientCity", "district", "recipientDistrict");

  private JushuitanCreditPushPolicy() {}

  static boolean ordinary(JsonNode order) {
    return order != null && "销售订单".equals(order.path("type").asText())
        && !order.hasNonNull("deletedAt") && !JushuitanDocumentService.isSettlement(order);
  }

  static boolean optedIn(JsonNode order) {
    return ordinary(order) && order.path("approvalDraft").path("creditFulfillment").isBoolean()
        && order.path("approvalDraft").path("creditFulfillment").booleanValue();
  }

  static void markSubmission(ObjectNode approval, JsonNode order, JsonNode products, String environment) {
    if (!optedIn(order)) return;
    validateSubmission(order);
    approval.put("creditPushPolicyVersion", SUBMISSION_VERSION).put("creditPushEnvironment", environment)
        .put("creditPushAccountingMode", ACCOUNTING_MODE);
    approval.set("creditPushProducts", products(order, products));
  }

  /** Submission-only constraints: incomplete drafts may still be saved. */
  static void validateSubmission(JsonNode order) {
    JushuitanLogistics.validateDraft(order);
    JushuitanLogistics.selection(order.path("approvalDraft"), true);
    if (!optedIn(order)) return;
    var draft = order.path("approvalDraft");
    var name = draft.path("recipientName"); var phone = draft.path("recipientPhone"); var address = draft.path("recipientAddress");
    require(name.isTextual() && !name.asText().strip().isEmpty() && name.asText().strip().length() <= 50, "按账期发货请填写收货人，最多50字");
    require(phone.isTextual() && phone.asText().strip().matches("[0-9+() -]{7,30}"), "按账期发货请填写有效收货电话，长度7至30字");
    require(address.isTextual() && CreditShippingAddress.validDraft(draft), CreditShippingAddress.MESSAGE);
  }

  static ArrayNode products(JsonNode order, JsonNode catalog) {
    Set<String> referenced = new HashSet<>();
    order.path("items").forEach(item -> referenced.add(item.path("product").asText()));
    ArrayNode result = JSON.arrayNode();
    for (var product : catalog) if (referenced.remove(product.path("id").asText())) {
      var copy = result.addObject();
      for (String field : PRODUCT_FIELDS) if (product.has(field)) copy.set(field, product.path(field).deepCopy());
    }
    return result;
  }

  static boolean sameApprovedContents(JsonNode snapshot, JsonNode order) {
    if (!snapshot.isObject()) return false;
    for (String field : APPROVED_FIELDS) {
      JsonNode left = snapshot.path(field), right = order.path(field);
      if (field.equals("approvalDraft")) {
        if (!JushuitanLogistics.sameDraft(left, right)) return false;
        continue;
      }
      if (!left.equals(right) && !(left.isNumber() && right.isNumber() && left.decimalValue().compareTo(right.decimalValue()) == 0)) return false;
    }
    return true;
  }

  static void validateDraft(JsonNode order) {
    JushuitanLogistics.validateDraft(order);
    var draft = order.path("approvalDraft");
    var credit = draft.path("creditFulfillment");
    require(credit.isMissingNode() || credit.isNull() || credit.isBoolean(), "账期推送选项无效");
    if (credit.asBoolean()) require(ordinary(order), "仅普通销售订单可设置账期推送");
    var shop = draft.path("shopId");
    if (!shop.isMissingNode() && !shop.isNull() && !(shop.isTextual() && shop.asText().isBlank()))
      require(!identifier(shop).isEmpty() && ordinary(order), "聚水潭推送店铺编号无效");
    var name = draft.path("shopName");
    require(name.isMissingNode() || name.isNull() || (name.isTextual() && name.asText().length() <= 191
        && name.asText().chars().noneMatch(Character::isISOControl)), "聚水潭店铺名称无效");
    if (!name.asText("").isBlank()) require(!identifier(shop).isEmpty(), "请选择聚水潭推送店铺，不能只填写名称");
    if (credit.asBoolean()) for (String field : RECIPIENT.values()) {
      var value = draft.path(field);
      int limit = field.equals("recipientAddress") ? 200 : field.equals("recipientPhone") ? 30 : 50;
      require(value.isMissingNode() || value.isNull() || (value.isTextual() && value.asText().length() <= limit
          && value.asText().chars().noneMatch(Character::isISOControl)), "账期订单收货资料格式或长度无效");
    }
  }

  static boolean samePlan(JsonNode left, JsonNode right) {
    var a = left.path("approvalDraft"); var b = right.path("approvalDraft");
    boolean same = identifier(a.path("shopId")).equals(identifier(b.path("shopId")))
        && a.path("shopName").asText("").strip().equals(b.path("shopName").asText("").strip())
        && a.path("creditFulfillment").asBoolean() == b.path("creditFulfillment").asBoolean()
        && JushuitanLogistics.selection(a, false).equals(JushuitanLogistics.selection(b, false));
    if (!same) return false;
    var fields = a.path("creditFulfillment").asBoolean() || b.path("creditFulfillment").asBoolean()
        ? RECIPIENT.values() : List.of("recipientProvince", "recipientCity", "recipientDistrict");
    for (String field : fields)
      if (!a.path(field).asText("").strip().equals(b.path(field).asText("").strip())) return false;
    return true;
  }

  static String fullAddress(JsonNode draft) {
    String address = draft.path("recipientAddress").asText().strip();
    var result = new StringBuilder();
    String previous = "";
    for (String field : List.of("recipientProvince", "recipientCity", "recipientDistrict")) {
      String value = draft.path(field).asText().strip();
      if (field.equals("recipientCity") && Set.of("市辖区", "县", "省直辖县级行政区划", "自治区直辖县级行政区划", "省直辖行政单位", "自治区直辖行政单位").contains(value)) continue;
      if (!value.isEmpty() && !value.equals(previous)) result.append(value);
      if (!value.isEmpty()) previous = value;
    }
    return result.append(address).toString();
  }

  static void enqueue(ObjectNode approval, JsonNode approvedOrder) {
    if (approval.path("creditPushPolicyVersion").asInt() != VERSION || !"APPROVED".equals(approval.path("status").asText())
        || !ordinary(approvedOrder) || approval.path(FIELD).isObject()) return;
    JsonNode snapshot = approval.path("orderSnapshot");
    var push = intent(approval, snapshot.isObject() ? snapshot : approvedOrder,
        approval.path("creditPushProducts"), approval.path("creditPushEnvironment").asText());
    if (!sameApprovedContents(snapshot, approvedOrder)) block(push, "审批订单快照不一致，请核对后补推");
    approval.set(FIELD, push);
  }

  static ObjectNode intent(JsonNode approval, JsonNode snapshot, JsonNode products, String environment) {
    return intent(approval, snapshot, products, environment, false);
  }

  static ObjectNode intentForSubmission(JsonNode approval, JsonNode snapshot, JsonNode products, String environment) {
    return intent(approval, snapshot, products, environment, true);
  }

  static boolean isParallelJob(JsonNode task) {
    return task.path("policyVersion").asInt() == SUBMISSION_VERSION
        && SUBMISSION_TRIGGER.equals(task.path("trigger").asText())
        && task.path("submissionId").asText().matches("[a-f0-9-]{36}");
  }

  private static ObjectNode intent(JsonNode approval, JsonNode snapshot, JsonNode products, String environment, boolean parallel) {
    ObjectNode order = snapshot.deepCopy();
    if (!parallel) order.put("status", "已批准");
    var push = JSON.objectNode().put("policyVersion", parallel ? SUBMISSION_VERSION : VERSION).put("orderId", approval.path("orderId").asText())
        .put("soId", "CRM-" + UUID.randomUUID().toString().replace("-", ""))
        .put("approvalUuid", approval.path("uuid").asText()).put("instanceCode", approval.path("instanceCode").asText())
        .put("accountingMode", approval.path("creditPushAccountingMode").asText())
        .put("environment", environment).put("status", "QUEUED").put("attempts", 0).put("attempted", false)
        .put("createdAt", CrmRepository.now()).put("updatedAt", CrmRepository.now());
    if (parallel) push.put("trigger", SUBMISSION_TRIGGER).put("submissionId", UUID.randomUUID().toString());
    push.set("orderSnapshot", order);
    push.set("products", products.isArray() ? products.deepCopy() : JSON.arrayNode());
    push.set("input", input(order, push.path("products")));
    ((ObjectNode)push.path("input")).put("accountingMode", push.path("accountingMode").asText());
    validateIntent(push);
    return push;
  }

  static void validateIntent(ObjectNode push) {
    List<String> missing = new ArrayList<>();
    var input = push.path("input");
    if (!ACCOUNTING_MODE.equals(push.path("accountingMode").asText())
        || !ACCOUNTING_MODE.equals(input.path("accountingMode").asText())) missing.add("已确认的账期挂账（非实收）口径");
    if (identifier(input.path("shopId")).isEmpty()) missing.add("聚水潭店铺");
    if (identifier(input.path("warehouseId")).isEmpty()) missing.add("发货仓库");
    var recipient = input.path("recipient");
    String name = recipient.path("name").asText().strip(), phone = recipient.path("phone").asText().strip();
    String address = recipient.path("address").asText().strip();
    if (name.isEmpty() || name.length() > 50) missing.add("有效收货人");
    if (!phone.matches("[0-9+() -]{7,30}")) missing.add("有效收货电话");
    if (!CreditShippingAddress.valid(recipient)) missing.add("完整收货地址（省、市、区县及详细地址）");
    try { JushuitanLogistics.requirePlan(push.path("orderSnapshot").path("approvalDraft"), input); }
    catch (com.emie.crm.common.exception.ApiException failure) { missing.add("与提交计划一致的有效物流公司或仓库安排"); }
    if (!Set.of("sandbox", "production").contains(push.path("environment").asText())) missing.add("有效聚水潭环境");
    if (push.path("policyVersion").asInt() == SUBMISSION_VERSION) {
      if (!isParallelJob(push)) missing.add("有效订单提交身份");
    } else if (push.path("approvalUuid").asText().isBlank() || push.path("instanceCode").asText().isBlank()) missing.add("有效审批实例");
    if (!push.path("orderSnapshot").path("items").isArray() || push.path("orderSnapshot").path("items").isEmpty()
        || !input.path("items").isArray() || input.path("items").isEmpty()
        || input.path("items").findValuesAsText("skuId").stream().anyMatch(String::isBlank)) missing.add("可用商品SKU");
    push.remove("missingFields");
    if (!missing.isEmpty()) {
      var rows = push.putArray("missingFields"); missing.forEach(rows::add);
      block(push, "账期订单待补齐：" + String.join("、", missing));
    } else {
      push.put("status", "QUEUED").put("message", isParallelJob(push)
          ? "订单已提交，等待推送聚水潭供财务审核；飞书审批独立进行" : "审批已通过，等待自动推送聚水潭账期订单");
    }
  }

  static void block(ObjectNode push, String message) {
    push.put("status", "BLOCKED").put("message", message).put("updatedAt", CrmRepository.now());
  }

  static ObjectNode input(JsonNode snapshot, JsonNode products) {
    var draft = snapshot.path("approvalDraft");
    var input = JSON.objectNode().put("creditFulfillment", true);
    for (String field : List.of("shopId", "warehouseId")) putIdentifier(input, field, draft.path(field));
    input.put("shopName", draft.path("shopName").asText()).put("warehouseName", draft.path("warehouseName").asText());
    JushuitanLogistics.copyPlan(input, draft);
    var recipient = input.putObject("recipient");
    RECIPIENT.forEach((key, field) -> {
      String value = draft.path(field).asText().strip();
      if (!Set.of("province", "city", "district").contains(key) || !value.isEmpty()) recipient.put(key, value);
    });
    var catalog = new HashMap<String, JsonNode>();
    products.forEach(product -> catalog.put(product.path("id").asText(), product));
    var lines = input.putArray("items");
    for (var item : snapshot.path("items")) {
      var product = catalog.get(item.path("product").asText());
      lines.addObject().put("skuId", product == null ? "" : product.path("jstSku").asText().strip());
    }
    return input;
  }

  static void supplement(ObjectNode push, JsonNode details) {
    var input = (ObjectNode) push.path("input");
    var original = push.path("orderSnapshot").path("approvalDraft");
    JushuitanLogistics.requireUnchangedSupplement(original, details);
    for (String field : List.of("shopId", "warehouseId")) if (details.has(field)) {
      String approved = identifier(original.path(field)), chosen = identifier(details.path(field));
      require(!chosen.isEmpty() && (approved.isEmpty() || approved.equals(chosen)), "推送店铺或仓库与审批资料不一致");
      putIdentifier(input, field, details.path(field));
    }
    for (String field : List.of("shopName", "warehouseName")) if (details.path(field).isTextual())
      input.put(field, details.path(field).asText().strip());
    var recipient = (ObjectNode) input.path("recipient");
    RECIPIENT.forEach((key, field) -> {
      JsonNode supplied = details.path("recipient").has(key) ? details.path("recipient").path(key) : details.path(field);
      if (supplied.isMissingNode()) return;
      require(supplied.isTextual(), "收货资料无效");
      String approved = original.path(field).asText().strip(), value = supplied.asText().strip();
      if (push.path("shippingAddressAuthorization").isObject() && Set.of("province", "city", "district", "address").contains(key))
        require(recipient.path(key).asText().equals(value), "收货地址已绑定单次明确授权，不能通过通用补齐接口修改");
      require(approved.isEmpty() || approved.equals(value), "推送收货资料与审批内容不一致");
      if (Set.of("province", "city", "district").contains(key) && value.isEmpty()) recipient.remove(key);
      else recipient.put(key, value);
    });
    validateIntent(push);
  }

  private static void putIdentifier(ObjectNode input, String field, JsonNode value) {
    String id = identifier(value);
    if (!id.isEmpty()) input.put(field, Long.parseLong(id)); else input.put(field, "");
  }

  static String identifier(JsonNode raw) {
    if (!raw.isTextual() && !raw.isIntegralNumber()) return "";
    String id = raw.asText().strip();
    return id.matches("[1-9][0-9]{0,18}") && (id.length() < 19 || id.compareTo(Long.toString(Long.MAX_VALUE)) <= 0) ? id : "";
  }

  static ObjectNode summary(JsonNode push) {
    var result = JSON.objectNode();
    for (String field : List.of("status", "message", "soId", "oId", "environment", "accountingMode", "policyVersion", "trigger", "submissionId", "createdAt", "updatedAt", "attempts", "missingFields"))
      if (push.has(field)) result.set(field, push.path(field).deepCopy());
    result.put("orderId", push.path("orderId").asText());
    result.put("retryable", Set.of("BLOCKED", "FAILED", "UNKNOWN").contains(push.path("status").asText()));
    return result;
  }
}
