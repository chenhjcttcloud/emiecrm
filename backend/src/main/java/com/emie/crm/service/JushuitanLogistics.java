package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** An explicit ERP company choice, or the unchanged default of warehouse-arranged logistics. */
final class JushuitanLogistics {
  static final List<String> FIELDS = List.of("logisticsMode", "logisticsCode", "logisticsCompany");
  record Selection(String mode, String code, String company) {
    boolean carrier() { return "CARRIER".equals(mode); }
  }

  private JushuitanLogistics() {}

  static Selection selection(JsonNode fields) { return selection(fields, true); }

  static Selection selection(JsonNode fields, boolean complete) {
    require(fields.isMissingNode() || fields.isNull() || fields.isObject(), "物流计划格式无效");
    String mode = text(fields.path("logisticsMode"), 20, "物流安排方式");
    if (mode.isEmpty()) mode = "WAREHOUSE";
    require(Set.of("WAREHOUSE", "CARRIER").contains(mode), "请选择仓库安排或指定物流");
    String code = text(fields.path("logisticsCode"), 100, "物流公司编码");
    String company = text(fields.path("logisticsCompany"), 191, "物流公司名称");
    if (mode.equals("WAREHOUSE")) require(code.isEmpty() && company.isEmpty(), "仓库安排物流时不能指定物流公司");
    else if (complete) require(!code.isEmpty() && !company.isEmpty(), "请从所选仓库的聚水潭目录选择物流公司");
    return new Selection(mode, code, company);
  }

  static void validateDraft(JsonNode order) {
    var choice = selection(order.path("approvalDraft"), false);
    if (choice.carrier()) require("销售订单".equals(order.path("type").asText()) && !JushuitanDocumentService.isSettlement(order),
        "仅普通销售订单可指定物流公司");
  }

  static Selection requirePlan(JsonNode draft, JsonNode input) {
    var planned = selection(draft, true);
    require(planned.equals(selection(input, true)), "物流安排与订单提交计划不一致，请核对原计划，不能在推单时更换物流");
    return planned;
  }

  static void apply(ObjectNode request, JsonNode draft, JsonNode input) {
    var planned = requirePlan(draft, input);
    if (planned.carrier()) request.put("lc_id", planned.code()).put("logistics_company", planned.company());
  }

  static void copyPlan(ObjectNode input, JsonNode draft) {
    // Preserve absence for legacy orders; adding a default must not alter a frozen intent.
    for (String field : FIELDS) if (draft.has(field)) input.set(field, draft.path(field).deepCopy());
  }

  static boolean sameDraft(JsonNode left, JsonNode right) {
    try {
      var a = selection(left, false); var b = selection(right, false);
      if (!a.equals(b)) return false;
      ObjectNode old = left.isObject() ? left.deepCopy() : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      ObjectNode next = right.isObject() ? right.deepCopy() : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      if (!a.carrier()) { old.remove(FIELDS); next.remove(FIELDS); }
      return old.equals(next);
    } catch (com.emie.crm.common.exception.ApiException invalid) { return false; }
  }

  static void requireUnchangedSupplement(JsonNode draft, JsonNode details) {
    if (FIELDS.stream().noneMatch(details::has)) return;
    ObjectNode proposed = draft.isObject() ? draft.deepCopy() : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    for (String field : FIELDS) if (details.has(field)) proposed.set(field, details.path(field));
    require(selection(draft, false).equals(selection(proposed, false)), "物流计划已随订单提交，不能通过补齐资料更换物流");
  }

  static String text(JsonNode value, int limit, String label) {
    if (value.isMissingNode() || value.isNull()) return "";
    require(value.isTextual(), label + "须为文本");
    String text = value.asText();
    require(text.length() <= limit && text.equals(text.strip()) && text.chars().noneMatch(Character::isISOControl), label + "格式或长度无效");
    return text;
  }
}
