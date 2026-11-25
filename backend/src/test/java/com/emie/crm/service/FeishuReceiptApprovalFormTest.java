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

  private void paymentDetails(String secondMethod) {
    var details = batch.putArray("paymentDetails");
    details.addObject().put("lineNo", 1).put("method", "银行转账").put("amount", 150).put("receiptDate", "2026-11-17");
    details.addObject().put("lineNo", 2).put("method", secondMethod).put("amount", 150).put("receiptDate", "2026-11-18");
    batch.put("method", "银行转账".equals(secondMethod) ? "银行转账" : "组合收款");
    ReceiptPaymentDetails.freezeAllocations(details, (ArrayNode) batch.path("allocations"));
  }

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

  @Test void receiptProjectUsesTheFrozenBusinessNumberWithoutChangingItsAllocationId() {
    ((ObjectNode) allocation(0).path("orderSnapshot")).put("id", "order-1").put("orderNumber", "2026-11-00000001");
    var original = batch.deepCopy();
    String project = field(build(), "project").path("value").asText();
    assertTrue(project.contains("订单：2026-11-00000001 | 本次收款：200.00"));
    assertTrue(project.contains("内部记录ID：order-1"));
    assertTrue(project.contains("订单：order-2 | 本次收款：100.00"));
    assertEquals(original, batch);
  }

  @Test void multiplePaymentLinesShowEveryRealChannelDateAndFrozenOrderAllocation() {
    paymentDetails("自定义收款账户");
    var original = batch.deepCopy();
    var form = build();
    assertEquals("2026-11-18T00:00:00+08:00", field(form, "date").path("value").asText());
    assertEquals(300, field(form, "amount").path("value").asInt());
    String project = field(form, "project").path("value").asText();
    assertTrue(project.contains("收款日期汇总：2026-11-18（取收款明细最晚日期"));
    assertTrue(project.contains("第1笔 | 收款方式：银行转账 | 收款金额：150.00 元 | 实际到账日期：2026-11-17"));
    assertTrue(project.contains("第2笔 | 收款方式：自定义收款账户 | 收款金额：150.00 元 | 实际到账日期：2026-11-18"));
    String firstOrder = project.substring(project.indexOf("订单：order-1"), project.indexOf("订单：order-2"));
    assertTrue(firstOrder.contains("第1笔 | 收款方式：银行转账 | 分配金额：150.00 元 | 实际到账日期：2026-11-17"));
    assertTrue(firstOrder.contains("第2笔 | 收款方式：自定义收款账户 | 分配金额：50.00 元 | 实际到账日期：2026-11-18"));
    String secondOrder = project.substring(project.indexOf("订单：order-2"));
    assertFalse(secondOrder.contains("第1笔"));
    assertTrue(secondOrder.contains("第2笔 | 收款方式：自定义收款账户 | 分配金额：100.00 元 | 实际到账日期：2026-11-18"));
    assertEquals(original, batch);
  }

  @Test void repeatedChannelsOnDifferentDatesStaySeparateAndExistingLargeProjectsAreNotTruncated() {
    paymentDetails("银行转账");
    var items = allocation(0).putArray("items");
    for (int index = 0; index < 16; index++) items.addObject().put("name", "商品" + index + "大".repeat(480)).put("qty", 1).put("price", 1);
    String project = field(build(), "project").path("value").asText();
    assertTrue(project.length() > 5000);
    assertTrue(project.contains("第1笔 | 收款方式：银行转账 | 收款金额：150.00 元 | 实际到账日期：2026-11-17"));
    assertTrue(project.contains("第2笔 | 收款方式：银行转账 | 收款金额：150.00 元 | 实际到账日期：2026-11-18"));
    assertTrue(project.contains("商品15"));
    assertTrue(project.contains("订单：order-2"));
  }

  @Test void inconsistentFrozenPaymentRowsCannotProduceANativeApprovalForm() {
    paymentDetails("自定义收款账户");
    ((ObjectNode) allocation(0).path("paymentDetails").get(0)).put("amount", 149);
    assertThrows(ApiException.class, this::build);
  }

  @Test void allFiftySupportedPaymentLinesRemainPresentInTheNativeProject() {
    var details = batch.putArray("paymentDetails");
    for (int lineNo = 1; lineNo <= 50; lineNo++)
      details.addObject().put("lineNo", lineNo).put("method", "银行转账").put("amount", 6).put("receiptDate", "2026-11-18");
    ReceiptPaymentDetails.freezeAllocations(details, (ArrayNode) batch.path("allocations"));
    String project = field(build(), "project").path("value").asText();
    assertEquals(50, project.lines().filter(line -> line.contains("| 收款金额：6.00")).count());
    assertTrue(project.contains("第50笔 | 收款方式：银行转账 | 分配金额：6.00 元 | 实际到账日期：2026-11-18"));
  }

  @Test void historicalSinglePaymentFormsDoNotInventDetailedPaymentsOrChangeTheirOriginalSummary() {
    String project = field(build(), "project").path("value").asText();
    assertTrue(project.contains("收款方式：银行转账"));
    assertFalse(project.contains("逐笔收款明细"));
    assertFalse(project.contains("收款日期汇总"));
    assertFalse(batch.has("paymentDetails"));
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

  @Test void ignoresMissingOrForgedClientCategoriesButRejectsAmbiguousTemplateCategories() {
    batch.put("receiptCategory", "其他收入");
    assertEquals("sales", field(build(), "category").path("value").asText());
    batch.remove("receiptCategory");
    assertEquals("sales", field(build(), "category").path("value").asText());
    batch.putArray("receiptCategory").add("服务收入");
    var original = batch.deepCopy();
    assertEquals("sales", field(build(), "category").path("value").asText());
    assertEquals(original, batch);
    ((ArrayNode) widget("category").path("option")).addObject().put("text", "销售收入").put("value", "duplicate-sales");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("重复"));
  }

  @Test void aTemplateWithoutSalesIncomeCannotSilentlyUseItsFirstOrDefaultCategory() {
    widget("category").withArray("option").remove(1);
    widget("category").put("enable_default_value", true).put("widget_default_value", "services");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("销售收入"));
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
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("凭证"));
    widget("attachment").put("required", false);
    widget("amount").putObject("option").putArray("currencyRange").add("USD");
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("人民币"));
    widget("amount").putObject("option").putArray("currencyRange").add("CNY");
    widget("owner").putObject("option").put("multi", true);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("单选"));
  }

  @ParameterizedTest @ValueSource(strings={"attachmentV2", "image", "imageV2"})
  void frozenReceiptVouchersBecomeFileCodeArraysWithoutChangingPaymentAllocations(String type) {
    String assetId = "d".repeat(64);
    batch.putArray("attachments").addObject().put("name", "到账凭证.png").put("url", AssetService.PREFIX + assetId);
    widget("attachment").put("type", type).put("required", true);
    var original = batch.deepCopy();
    var form = build();
    assertEquals(type, field(form, "attachment").path("type").asText());
    assertTrue(field(form, "attachment").path("value").isArray() && field(form, "attachment").path("value").isEmpty());
    assertFalse(form.toString().contains(AssetService.PREFIX));
    var record = batch.deepCopy(); record.set("form", form);
    record.set(FeishuApprovalEvidence.FIELD, FeishuApprovalEvidence.plan(mapper, definition, batch.path("attachments")));
    var assets = mock(AssetService.class);
    var client = mock(FeishuApprovalClient.class);
    byte[] bytes = {(byte)137,80,78,71,13,10,26,10,0};
    when(assets.read(assetId)).thenReturn(new AssetService.Asset("image/png", bytes));
    when(client.uploadFile("receipt-voucher-1.png", "attachmentV2".equals(type) ? "attachment" : "image", "image/png", bytes))
        .thenReturn("receipt-voucher-file-code");
    FeishuApprovalEvidence.prepare(record, assets, client, ignored -> {});
    FeishuApprovalEvidence.beforeCreate(record, ignored -> {});
    assertEquals(mapper.createArrayNode().add("receipt-voucher-file-code"), field(record.path("form"), "attachment").path("value"));
    assertEquals(300, field(record.path("form"), "amount").path("value").asInt());
    assertEquals(original.path("allocations"), record.path("allocations"));
    assertEquals(original, batch);
    assertFalse(record.path("form").toString().contains(AssetService.PREFIX));
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void attachedImagesCannotBeSilentlyDroppedIfTheReceiptTemplateHasNoVoucherWidget() {
    batch.putArray("attachments").addObject().put("name", "凭证.png").put("url", AssetService.PREFIX + "d".repeat(64));
    ((ArrayNode) definition.path("form")).remove(6);
    assertTrue(assertThrows(ApiException.class, this::build).getMessage().contains("尚无"));
  }
}
