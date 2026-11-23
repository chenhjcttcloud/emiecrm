package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class FeishuSalesApprovalFormTest {
  @Test void aVerifiedPlannedWarehouseIsVisibleInTheExistingApprovalNote() throws Exception {
    var order = FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "华南发货仓");
    var form = build(FeishuApprovalFixtures.definition(), order);
    assertTrue(field(form, "note").path("value").asText().contains("计划发货仓库：华南发货仓（5）"));
    assertTrue(field(form, "note").path("value").asText().contains("订单备注"));
  }

  @Test void aSelectedWarehouseCannotBeSilentlyOmittedFromATemplateWithoutNotes() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.withArray("form").remove(4);
    definition.withArray("form").addObject().put("id", "order-id").put("name", "订单编号").put("type", "input");
    var order = FeishuApprovalFixtures.order();
    order.putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "华南发货仓");
    assertTrue(assertThrows(ApiException.class, () -> build(definition, order)).getMessage().contains("仓库"));
  }

  @Test void domesticTemplateMapsOrderAndLineAmountsWithoutAdditionalInput() throws Exception {
    var form = build(FeishuApprovalFixtures.definition(), FeishuApprovalFixtures.order());
    assertEquals("sale", field(form, "type").path("value").asText());
    assertEquals("CNY", field(form, "total").path("currency").asText());
    assertEquals("24", field(form, "total").path("value").asText());
    assertTrue(field(form, "note").path("value").asText().contains("o1"));
    var line = field(form, "items").path("value").get(0);
    assertEquals("黑色", field(line, "model").path("value").asText());
    assertEquals("24", field(line, "subtotal").path("value").asText());
  }
  @Test void rejectsUnknownRequiredFieldsWithTheirName() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.withArray("form").addObject().put("id", "other").put("name", "财务项目").put("type", "radioV2").put("required", true);
    var error = assertThrows(ApiException.class, () -> build(definition, FeishuApprovalFixtures.order()));
    assertTrue(error.getMessage().contains("财务项目"));
  }
  @Test void refusesTotalsThatDifferFromProducts() throws Exception {
    var order = FeishuApprovalFixtures.order().put("total", 25);
    assertThrows(ApiException.class, () -> build(FeishuApprovalFixtures.definition(), order));
  }
  @Test void prepaymentDoesNotRequireProductsOrAnEqualProductTotal() throws Exception {
    var order = FeishuApprovalFixtures.order().put("type", "预收款订单").put("total", 50);
    order.putArray("items");
    var form = build(FeishuApprovalFixtures.definition(), order);
    assertEquals("deposit", field(form, "type").path("value").asText());
    assertEquals("50", field(form, "total").path("value").asText());
    assertNull(field(form, "items"));
  }
  @Test void doesNotSilentlyIgnoreARequiredRecipient() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.withArray("form").addObject().put("id", "recipient").put("name", "收货人").put("type", "input").put("required", true);
    assertTrue(assertThrows(ApiException.class, () -> build(definition, FeishuApprovalFixtures.order())).getMessage().contains("收货人"));
  }
  @Test void acceptsJsonEncodedDefinitions() throws Exception {
    var definition = FeishuApprovalFixtures.definition();
    definition.put("form", definition.path("form").toString());
    assertFalse(build(definition, FeishuApprovalFixtures.order()).isEmpty());
  }
  private JsonNode build(JsonNode definition, JsonNode order) throws Exception {
    return FeishuSalesApprovalForm.build(FeishuApprovalFixtures.MAPPER, definition, order,
        FeishuApprovalFixtures.state().path("products"), FeishuApprovalFixtures.MAPPER.createObjectNode());
  }
  private JsonNode field(JsonNode fields, String id) {
    for (var field : fields) if (id.equals(field.path("id").asText())) return field;
    return null;
  }
}
