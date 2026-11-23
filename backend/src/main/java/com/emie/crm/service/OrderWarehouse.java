package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A planning choice only. It never submits a warehouse order or books a receipt. */
final class OrderWarehouse {
  record Selection(String id, String name) {
    boolean selected() { return !id.isEmpty(); }
  }

  private OrderWarehouse() {}

  static Selection selection(JsonNode fields) {
    JsonNode raw = fields.path("warehouseId");
    JsonNode rawName = fields.path("warehouseName");
    require(rawName.isMissingNode() || rawName.isNull() || rawName.isTextual(), "发货仓库名称无效");
    String name = fields.path("warehouseName").asText("").strip();
    require(name.length() <= 191 && name.chars().noneMatch(Character::isISOControl), "发货仓库名称无效");
    if (raw.isMissingNode() || raw.isNull() || (raw.isTextual() && raw.asText().isBlank())) {
      require(name.isEmpty(), "请选择计划发货仓库，不能只填写仓库名称");
      return new Selection("", "");
    }
    require(raw.isTextual() || raw.isIntegralNumber(), "发货仓库编号必须为有效的正整数");
    String id = raw.asText().strip();
    require(id.matches("[1-9][0-9]{0,18}")
        && (id.length() < 19 || id.compareTo(Long.toString(Long.MAX_VALUE)) <= 0), "发货仓库编号必须为有效的正整数");
    return new Selection(id, name);
  }

  static Selection planned(JsonNode order) { return selection(order.path("approvalDraft")); }

  static void validateDraft(JsonNode order) {
    var choice = planned(order);
    if (choice.selected()) require(!JushuitanDocumentService.isSettlement(order)
        && java.util.Set.of("销售订单", "海外提货单").contains(order.path("type").asText()),
        "仅普通销售订单和海外提货单可设置计划发货仓库");
  }

  static boolean same(JsonNode left, JsonNode right) { return planned(left).equals(planned(right)); }

  static Selection resolve(JsonNode order, JsonNode input, JushuitanService directory) {
    validateDraft(order);
    var choice = planned(order);
    var supplied = selection(input);
    require(choice.id().equals(supplied.id()), "订单与审批中的计划发货仓库不一致，请刷新后重新提交");
    if (!choice.selected()) return choice;
    require(directory != null, "发货仓库目录暂不可用，请重新加载后再提交审批");
    for (var warehouse : directory.warehouses()) {
      if (!choice.id().equals(warehouse.path("id").asText())) continue;
      String name = warehouse.path("name").asText().strip();
      require(!name.isEmpty() && name.length() <= 191 && name.chars().noneMatch(Character::isISOControl),
          "所选仓库名称无效，请核对聚水潭仓库资料");
      return new Selection(choice.id(), name);
    }
    throw new com.emie.crm.common.exception.ApiException("所选计划发货仓库不存在或未生效，请刷新仓库列表");
  }

  static void apply(ObjectNode order, Selection choice) {
    if (!choice.selected()) return;
    order.withObject("approvalDraft").put("warehouseId", choice.id()).put("warehouseName", choice.name());
  }

  static ObjectNode approval(JsonNode input, Selection choice) {
    ObjectNode result = input.isObject() ? input.deepCopy() : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    if (choice.selected()) result.put("warehouseId", choice.id()).put("warehouseName", choice.name());
    return result;
  }
}
