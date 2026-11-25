package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        {"id":"deposit-1","type":"预收款订单","owner":"订单签单人","customer":"客户甲","total":300,"method":"配置项：国内对公账户",
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

  private void detailedProposal() {
    order.put("method", "组合收款");
    var rows = order.putArray("paymentDetails");
    rows.addObject().put("method", "银行转账").put("amount", 100).put("receiptDate", "2026-11-17");
    rows.addObject().put("method", "POS").put("amount", 200).put("receiptDate", "2026-11-18");
    PrepaymentPaymentDetails.normalize(order);
    input.set("paymentDetails", order.path("paymentDetails").deepCopy());
  }

  @Test void displaysEveryProposedPaymentRowAndUsesTheLatestDateWithoutReplacingProductOrMoneySemantics() {
    detailedProposal(); var before = order.deepCopy(); var form = build();
    var project = field(form, "project").path("value").asText();
    assertTrue(project.contains("预收款金额：300.00"));
    assertTrue(project.contains("第1笔 | 收款方式：银行转账 | 预收款金额：100.00 元 | 发生日期：2026-11-17"));
    assertTrue(project.contains("第2笔 | 收款方式：POS | 预收款金额：200.00 元 | 发生日期：2026-11-18"));
    assertTrue(project.contains("明细最晚日期"));
    assertTrue(project.contains("商品合计：1200.00"));
    assertEquals("2026-11-18T00:00:00+08:00", field(form, "date").path("value").asText());
    assertEquals(300, field(form, "amount").path("value").asInt());
    assertEquals(before, order);
    assertTrue(field(form, "note").path("value").asText().contains("不代表已登记实际收款"));
  }

  @Test void fiftyPaymentRowsAreNotTruncatedAndMismatchedApprovalInputIsRejected() {
    order.put("method", "组合收款").put("total", 50);
    var rows = order.putArray("paymentDetails");
    for (int i = 1; i <= 50; i++) rows.addObject().put("method", "配置账户" + "甲".repeat(44) + (i < 10 ? "0" : "") + i)
        .put("amount", 1).put("receiptDate", "2026-11-18");
    ((ObjectNode) products.get(0)).put("name", "甲".repeat(500));
    ((ObjectNode) products.get(1)).put("name", "乙".repeat(500));
    PrepaymentPaymentDetails.normalize(order); input.set("paymentDetails", order.path("paymentDetails").deepCopy());
    var project = field(build(), "project").path("value").asText();
    assertTrue(project.length() > 5000); assertTrue(project.contains("第50笔"));
    assertEquals(50, project.lines().filter(line -> line.contains(" | 预收款金额：1.00 元")).count());
    ((ObjectNode) input.path("paymentDetails").get(0)).put("amount", 2);
    assertThrows(ApiException.class, this::build);
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
    assertTrue(project.contains("收款方式：配置项：国内对公账户"));
    assertTrue(project.contains("商品合计：1200.00"));
    assertTrue(project.contains("SKU-1") && project.contains("SKU-2") && project.contains("白色"));
    assertEquals("ou_bound_owner", field(form, "owner").path("open_ids").get(0).asText());
    assertFalse(field(form, "owner").has("value"));
    assertFalse(form.toString().contains("secret"));
    assertFalse(form.toString().contains("shipment"));
    assertTrue(field(form, "note").path("value").asText().contains("不代表已登记实际收款"));
  }

  @Test void businessNumberOnlyChangesTheVisibleProjectReference() {
    order.put("orderNumber", "2026-11-00000001");
    var original = order.deepCopy();
    String project = field(build(), "project").path("value").asText();
    assertTrue(project.contains("CRM 订单：2026-11-00000001"));
    assertTrue(project.contains("内部记录ID：deposit-1"));
    assertEquals(original, order);
  }

  @Test void ignoresClientSuppliedMoneyCustomerAndOwnerInFavorOfSavedOrderAndBinding() {
    input.put("total", 999).put("customer", "伪造客户").put("ownerOpenId", "ou_forged").put("method", "伪造收款方式");
    var form = build();
    assertEquals(300, field(form, "amount").path("value").asInt());
    assertEquals("客户甲", field(form, "customer").path("value").asText());
    assertEquals("ou_bound_owner", field(form, "owner").path("open_ids").get(0).asText());
    assertTrue(field(form, "project").path("value").asText().contains("收款方式：配置项：国内对公账户"));
    assertFalse(form.toString().contains("伪造收款方式"));
  }

  @Test void displaysTheSavedConfiguredPaymentMethodAndKeepsMissingLegacyMethodsUnspecified() {
    order.put("method", "自定义配置的收款账户");
    assertTrue(field(build(), "project").path("value").asText().contains("收款方式：自定义配置的收款账户"));
    order.remove("method");
    assertFalse(field(build(), "project").path("value").asText().contains("收款方式："));
    order.putObject("method").put("name", "不能隐式读取对象");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("收款方式"));
  }

  @Test void allowsAnUnspecifiedProductListWithoutInventingAProduct() {
    order.putArray("items");
    String project = field(build(), "project").path("value").asText();
    assertTrue(project.contains("商品明细：未填写"));
    assertFalse(project.contains("SKU-"));
    assertFalse(project.contains("\n商品合计："));
  }

  @Test void validatesTheExplicitBusinessDateWithoutRequiringAClientCategory() {
    input.put("receiptDate", "2026-02-30");
    assertEquals(422, assertThrows(ApiException.class, this::build).status());
    input.put("receiptDate", "2026-11-18").put("receiptCategory", "不支持的类别");
    assertEquals("income-sales", field(build(), "category").path("value").asText());
    input.remove("receiptCategory");
    assertEquals("income-sales", field(build(), "category").path("value").asText());
    input.putObject("receiptCategory").put("forged", "其他收入");
    assertEquals("income-sales", field(build(), "category").path("value").asText());
  }

  @Test void automaticSalesCategoryUsesTheCurrentTemplateValueAndDoesNotMutateInput() {
    var options = ((ObjectNode) field(definition.path("form"), "category")).withArray("option");
    var sales = (ObjectNode) options.remove(0);
    options.add(sales.put("value", "$i18n-sales-current/value"));
    input.put("receiptCategory", "其他收入");
    var originalInput = input.deepCopy(); var originalOrder = order.deepCopy();
    assertEquals("$i18n-sales-current/value", field(build(), "category").path("value").asText());
    assertEquals(originalInput, input); assertEquals(originalOrder, order);
    options.remove(1);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("销售收入"));
  }

  @Test void requiresTheAuthoritativeOwnerBinding() {
    var error = assertThrows(ApiException.class, () ->
        FeishuSalesApprovalForm.build(mapper, definition, order, products, input));
    assertEquals(422, error.status());
    assertTrue(error.getMessage().contains("签单人"));
  }

  @Test void requiresVoucherImagesWhenConfiguredAndRejectsOtherUnsupportedRequiredFields() {
    ((ObjectNode) field(definition.path("form"), "attachment")).put("required", true);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("凭证"));
    ((ObjectNode) field(definition.path("form"), "attachment")).put("required", false);
    definition.withArray("form").addObject().put("id", "budget").put("name", "预算科目").put("type", "input").put("required", true);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("预算科目"));
  }

  @ParameterizedTest @ValueSource(strings={"attachmentV2", "image", "imageV2"})
  void frozenVoucherImagesBecomeFileCodeArraysWithoutChangingTheDepositAmount(String type) {
    String assetId = "c".repeat(64);
    order.putArray("attachments").addObject().put("name", "预收款凭证.png").put("url", AssetService.PREFIX + assetId);
    ((ObjectNode) field(definition.path("form"), "attachment")).put("type", type).put("required", true);
    var original = order.deepCopy();
    var form = build();
    var placeholder = field(form, "attachment");
    assertEquals(type, placeholder.path("type").asText());
    assertTrue(placeholder.path("value").isArray() && placeholder.path("value").isEmpty());
    assertFalse(form.toString().contains(AssetService.PREFIX));
    var record = mapper.createObjectNode();
    record.set("orderSnapshot", order.deepCopy()); record.set("form", form);
    record.set(FeishuApprovalEvidence.FIELD, FeishuApprovalEvidence.plan(mapper, definition, order.path("attachments")));
    var assets = mock(AssetService.class);
    var client = mock(FeishuApprovalClient.class);
    byte[] bytes = {(byte)137,80,78,71,13,10,26,10,0};
    when(assets.read(assetId)).thenReturn(new AssetService.Asset("image/png", bytes));
    when(client.uploadFile("receipt-voucher-1.png", "attachmentV2".equals(type) ? "attachment" : "image", "image/png", bytes))
        .thenReturn("deposit-voucher-file-code");
    FeishuApprovalEvidence.prepare(record, assets, client, ignored -> {});
    FeishuApprovalEvidence.beforeCreate(record, ignored -> {});
    assertEquals(mapper.createArrayNode().add("deposit-voucher-file-code"), field(record.path("form"), "attachment").path("value"));
    assertEquals(300, field(record.path("form"), "amount").path("value").asInt());
    assertEquals(original, order);
    assertFalse(record.path("form").toString().contains(AssetService.PREFIX));
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void attachedImagesCannotBeSilentlyDroppedIfThePrepaymentTemplateHasNoVoucherWidget() {
    order.putArray("attachments").addObject().put("name", "凭证.png").put("url", AssetService.PREFIX + "c".repeat(64));
    ((ArrayNode) definition.path("form")).remove(6);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("尚无"));
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
