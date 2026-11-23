package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeishuReceiptApprovalFormTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ObjectNode definition;
  private ObjectNode batch;

  @BeforeEach void prepare() throws Exception {
    definition = (ObjectNode) mapper.readTree("""
        {"form":[
          {"id":"project","name":"项目","type":"textarea","required":true},
          {"id":"customer","name":"客户","type":"input","required":true},
          {"id":"date","name":"收款发生日期","type":"date","required":true},
          {"id":"category","name":"收款类别","type":"radioV2","required":true,
           "option":[{"text":"服务收入","value":"services"},{"text":"销售收入","value":"sales"}]},
          {"id":"amount","name":"收款金额（元）","type":"amount","required":true,"option":{"currencyRange":["CNY"]}},
          {"id":"owner","name":"归属人","type":"contact","required":true,"option":{"multi":false}},
          {"id":"attachment","name":"附件","type":"attachmentV2","required":false},
          {"id":"note","name":"备注","type":"textarea","required":false}
        ]}
        """);
    batch = (ObjectNode) mapper.readTree("""
        {"id":"receipt-batch-1","customer":"客户甲","amount":300,"method":"银行转账",
         "receiptDate":"2026-11-18","receiptCategory":"销售收入","operator":"登记人甲","note":"分两张单收款",
         "allocations":[
          {"orderId":"order-1","amount":200,"orderSnapshot":{"owner":"签单人甲","private":"secret"},
           "items":[{"product":"p1","name":"商品甲","sku":"SKU-1","model":"白色","qty":1,"price":1000,"cost":999}]},
          {"orderId":"order-2","amount":100,"orderSnapshot":{"owner":"签单人乙"},
           "items":[{"product":"p2","name":"商品乙","sku":"SKU-2","model":"蓝色","qty":2,"price":200}]}
         ]}
        """);
  }

  private ArrayNode build() {
    return FeishuReceiptApprovalForm.build(mapper, definition, batch, "ou_receipt_operator");
  }

  private JsonNode field(JsonNode form, String id) {
    for (var field : form) if (id.equals(field.path("id").asText())) return field;
    fail("Missing field: " + id);
    return null;
  }

  private ObjectNode widget(String id) { return (ObjectNode) field(definition.path("form"), id); }
  private ObjectNode allocation(int index) { return (ObjectNode) batch.path("allocations").get(index); }

  @Test void submitsTheReceiptAllocationTotalInsteadOfTheGoodsOrFullOrderValue() {
    var form = build();
    assertEquals(0, new java.math.BigDecimal("300.00").compareTo(field(form, "amount").path("value").decimalValue()));
    assertEquals("CNY", field(form, "amount").path("currency").asText());
    assertEquals("客户甲", field(form, "customer").path("value").asText());
    assertEquals("2026-11-18T00:00:00+08:00", field(form, "date").path("value").asText());
    assertEquals("sales", field(form, "category").path("value").asText());
    var project = field(form, "project").path("value").asText();
    assertTrue(project.contains("本次收款合计：300.00"));
    assertTrue(project.contains("order-1 | 本次收款：200.00"));
    assertTrue(project.contains("order-2 | 本次收款：100.00"));
    assertTrue(project.contains("商品合计：1000.00") && project.contains("商品合计：400.00"));
    assertTrue(project.contains("SKU-1") && project.contains("SKU-2") && project.contains("白色"));
    assertTrue(project.contains("签单人甲") && project.contains("签单人乙"));
    assertTrue(field(form, "note").path("value").asText().contains("审批通过后才计入订单已收金额，不触发聚水潭发货"));
    assertFalse(form.toString().contains("secret"));
    assertFalse(form.toString().contains("cost"));
  }

  @Test void usesTheBoundReceiptOperatorAndNeverUsesANominatedContactFromTheBatch() {
    batch.put("ownerOpenId", "ou_forged");
    var form = build();
    var owner = field(form, "owner");
    assertEquals("ou_receipt_operator", owner.path("open_ids").get(0).asText());
    assertFalse(owner.has("value"));
    assertFalse(form.toString().contains("ou_forged"));
    assertEquals(422, assertThrows(ApiException.class, () ->
        FeishuReceiptApprovalForm.build(mapper, definition, batch, null)).status());
    assertEquals(422, assertThrows(ApiException.class, () ->
        FeishuReceiptApprovalForm.build(mapper, definition, batch, "not-an-open-id")).status());
  }

  @Test void resolvesTheCategoryOptionAtSubmissionTimeAndAcceptsSerializedDefinitions() throws Exception {
    ((ObjectNode) widget("category").path("option").get(1)).put("value", "updated-sales-option");
    definition.put("form", mapper.writeValueAsString(definition.path("form")));
    assertEquals("updated-sales-option", field(build(), "category").path("value").asText());
  }

  @Test void rejectsMissingUnsupportedOrAmbiguousCategories() {
    batch.put("receiptCategory", "其他收入");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    batch.put("receiptCategory", "销售收入");
    ((ArrayNode) widget("category").path("option")).addObject().put("text", "销售收入").put("value", "duplicate-sales");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("重复"));
  }

  @Test void validatesTheBusinessDateStrictly() {
    for (String invalid : new String[]{"2026-02-29", "2026-02-30", "2026-2-3", "2026-11-18T00:00:00Z", ""}) {
      batch.put("receiptDate", invalid);
      assertEquals(422, assertThrows(ApiException.class, this::build).status());
    }
    batch.put("receiptDate", "2024-02-29");
    assertEquals("2024-02-29T00:00:00+08:00", field(build(), "date").path("value").asText());
  }

  @Test void rejectsInvalidMoneyAndPaymentAllocationMismatch() {
    for (String invalid : new String[]{"0", "-1", "300.001", "1000000000000", "NaN", ""}) {
      batch.put("amount", invalid);
      assertEquals(422, assertThrows(ApiException.class, this::build).status());
    }
    batch.put("amount", 301);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("合计不一致"));
    batch.put("amount", 300);
    allocation(0).put("amount", 0);
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    allocation(0).put("amount", "200.001");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
  }

  @Test void rejectsDuplicateOrMissingOrderAllocations() {
    allocation(1).put("orderId", "order-1");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("重复分配"));
    batch.putArray("allocations");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
  }

  @Test void permitsEmptyProductSnapshotsWithoutInventingProducts() {
    allocation(0).putArray("items");
    var project = field(build(), "project").path("value").asText();
    assertTrue(project.contains("商品明细：未填写"));
    assertFalse(project.contains("SKU-1"));
    assertTrue(project.contains("SKU-2"));
  }

  @Test void validatesTheSnapshotFormatQuantityAndPrice() {
    ((ObjectNode) allocation(0).path("items").get(0)).put("qty", 0);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("商品数量"));
    ((ObjectNode) allocation(0).path("items").get(0)).put("qty", 1).put("price", "-1");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("商品单价"));
    allocation(0).put("items", "invalid");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("快照格式"));
  }

  @Test void rejectsMissingFieldsDuplicateNamesOrIdsAndWrongWidgetTypes() {
    widget("customer").put("type", "textarea");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    widget("customer").put("type", "input");
    var widgets = (ArrayNode) definition.path("form");
    widgets.add(widget("customer").deepCopy().put("id", "other-customer"));
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    widgets.remove(widgets.size() - 1);
    widgets.addObject().put("id", "customer").put("name", "无关选填项").put("type", "input");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    widgets.remove(widgets.size() - 1);
    widgets.remove(1);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("缺少字段"));
  }

  @Test void rejectsNewRequiredWidgetsAndRequiresCnyAndSingleContact() {
    widget("attachment").put("required", true);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("附件"));
    widget("attachment").put("required", false);
    widget("amount").putObject("option").putArray("currencyRange").add("USD");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("人民币"));
    widget("amount").putObject("option").putArray("currencyRange").add("CNY");
    widget("owner").putObject("option").put("multi", true);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("单选"));
  }
}
