package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeishuPrepaymentApprovalFormTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ObjectNode definition;
  private ObjectNode order;
  private JsonNode products;
  private ObjectNode input;

  @BeforeEach void prepare() throws Exception {
    definition = (ObjectNode) mapper.readTree("""
        {"form":[
          {"id":"project","name":"项目","type":"textarea","required":true},
          {"id":"customer","name":"客户","type":"input","required":true},
          {"id":"date","name":"收款发生日期","type":"date","required":true},
          {"id":"category","name":"收款类别","type":"radioV2","required":true,
           "option":[{"text":"销售收入","value":"income-sales"},{"text":"其他收入","value":"income-other"}]},
          {"id":"amount","name":"收款金额（元）","type":"amount","required":true,"option":{"currencyRange":["CNY"]}},
          {"id":"owner","name":"归属人","type":"contact","required":true},
          {"id":"attachment","name":"附件","type":"attachmentV2","required":false},
          {"id":"note","name":"备注","type":"textarea","required":false}
        ]}
        """);
    order = (ObjectNode) mapper.readTree("""
        {"id":"deposit-1","type":"预收款订单","owner":"订单签单人","customer":"客户甲","total":300,
         "items":[{"product":"p1","qty":1,"price":1000},{"product":"p2","qty":1,"price":200}],"note":"客户定金"}
        """);
    products = mapper.readTree("""
        [{"id":"p1","name":"商品甲","jstSku":"SKU-1","model":"白色","cost":999,"private":"secret"},
         {"id":"p2","name":"商品乙","code":"SKU-2"}]
        """);
    input = mapper.createObjectNode().put("receiptDate", "2026-11-18").put("receiptCategory", "销售收入");
  }

  private ArrayNode build() {
    return FeishuSalesApprovalForm.build(mapper, definition, order, products, input, "ou_bound_owner");
  }

  private JsonNode field(JsonNode form, String id) {
    for (var field : form) if (id.equals(field.path("id").asText())) return field;
    fail("Missing field: " + id);
    return null;
  }

  @Test void mapsTheDedicatedFormWithoutReplacingTheDepositWithGoodsValue() {
    var form = build();
    assertEquals(300, field(form, "amount").path("value").asInt());
    assertEquals("CNY", field(form, "amount").path("currency").asText());
    assertEquals("客户甲", field(form, "customer").path("value").asText());
    assertEquals("2026-11-18T00:00:00+08:00", field(form, "date").path("value").asText());
    assertEquals("income-sales", field(form, "category").path("value").asText());
    var project = field(form, "project").path("value").asText();
    assertTrue(project.contains("预收款金额：300.00"));
    assertTrue(project.contains("商品合计：1200.00"));
    assertTrue(project.contains("SKU-1") && project.contains("SKU-2") && project.contains("白色"));
    assertEquals("ou_bound_owner", field(form, "owner").path("open_ids").get(0).asText());
    assertFalse(field(form, "owner").has("value"));
    assertFalse(form.toString().contains("secret"));
    assertFalse(form.toString().contains("shipment"));
    assertTrue(field(form, "note").path("value").asText().contains("不代表已登记实际收款"));
  }

  @Test void ignoresClientSuppliedMoneyCustomerAndOwnerInFavorOfSavedOrderAndBinding() {
    input.put("total", 999).put("customer", "伪造客户").put("ownerOpenId", "ou_forged");
    var form = build();
    assertEquals(300, field(form, "amount").path("value").asInt());
    assertEquals("客户甲", field(form, "customer").path("value").asText());
    assertEquals("ou_bound_owner", field(form, "owner").path("open_ids").get(0).asText());
  }

  @Test void allowsAnUnspecifiedProductListWithoutInventingAProduct() {
    order.putArray("items");
    String project = field(build(), "project").path("value").asText();
    assertTrue(project.contains("商品明细：未填写"));
    assertFalse(project.contains("SKU-"));
    assertFalse(project.contains("\n商品合计："));
  }

  @Test void validatesTheExplicitBusinessDateAndCategory() {
    input.put("receiptDate", "2026-02-30");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    input.put("receiptDate", "2026-11-18").put("receiptCategory", "不支持的类别");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    input.remove("receiptCategory");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
  }

  @Test void requiresTheAuthoritativeOwnerBinding() {
    var error = assertThrows(ApiException.class, () ->
        FeishuSalesApprovalForm.build(mapper, definition, order, products, input));
    assertEquals(422, error.status());
    assertTrue(error.getMessage().contains("签单人"));
  }

  @Test void rejectsNewRequiredFieldsInsteadOfFabricatingValues() {
    ((ObjectNode) field(definition.path("form"), "attachment")).put("required", true);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("附件"));
  }

  @Test void acceptsSerializedDefinitionsAndRequiresCny() throws Exception {
    var widgets = definition.path("form").deepCopy();
    definition.put("form", mapper.writeValueAsString(widgets));
    assertTrue(FeishuSalesApprovalForm.usesDedicatedPrepaymentForm(mapper, definition));
    assertEquals(300, field(build(), "amount").path("value").asInt());
    definition.set("form", widgets);
    ((ObjectNode) field(widgets, "amount")).putObject("option").putArray("currencyRange").add("USD");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("人民币"));
  }

  @Test void rejectsDuplicateMappedFieldsAndInvalidProductQuantities() {
    var widgets = (ArrayNode) definition.path("form");
    widgets.add(field(widgets, "customer").deepCopy());
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    widgets.remove(widgets.size() - 1);
    ((ObjectNode) order.path("items").get(0)).put("qty", 0);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("商品数量"));
  }
}
