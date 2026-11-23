package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private CrmRepository db;
  private BusinessService service;
  private JsonNode stored;
  private List<JsonNode> history;
  private final Actor admin = new Actor("admin", "管理员", Set.of("business.read", "business.write", "order.approve", "payment.write"));
  private final Actor sales = new Actor("sales", "销售", Set.of("business.read", "business.write"));

  private JsonNode json(String text) throws Exception { return mapper.readTree(text); }

  @BeforeEach void setup() throws Exception {
    stored = json("""
        {"revision":1,"customers":[{"id":"c1","name":"客户甲","balance":0}],
         "products":[{"id":"p1","name":"产品","source":"jushuitan","enabled":true}],
         "orders":[{"id":"o1","customerId":"c1","customer":"客户甲","type":"销售订单",
          "status":"已批准","total":100,"paid":0,"items":[{"product":"p1","qty":1,"price":100}]}]}
        """);
    history = new ArrayList<>();
    db = mock(CrmRepository.class);
    when(db.jdbc()).thenReturn(mock(org.springframework.jdbc.core.JdbcTemplate.class));
    when(db.state(anyBoolean())).thenAnswer(call -> stored.deepCopy());
    when(db.documents(eq("receipts"), anyBoolean(), eq(0))).thenAnswer(call -> history.stream().map(JsonNode::deepCopy).toList());
    when(db.object()).thenAnswer(call -> mapper.createObjectNode());
    when(db.rows(anyString(), anyString())).thenReturn(List.of(Map.of("id", "bank")));
    doAnswer(call -> { stored = ((JsonNode) call.getArgument(0)).deepCopy(); return null; }).when(db).state(any(JsonNode.class));
    doAnswer(call -> { history.add(((JsonNode) call.getArgument(0)).deepCopy()); return null; }).when(db).receipt(any());
    service = new BusinessService(db, mock(AssetService.class));
  }

  private ObjectNode draft() { return stored.deepCopy(); }
  private ObjectNode order(JsonNode state) { return (ObjectNode) state.path("orders").get(0); }
  private JsonNode save(JsonNode state, Actor actor) {
    var body = mapper.createObjectNode(); body.set("data", state); body.put("revision", state.path("revision").asLong());
    return service.save(body, actor);
  }

  @Test void aDraftCanKeepAnOptionalPlanningWarehouseWithoutChangingItsMoney() {
    order(stored).put("status", "草稿");
    var changed = draft(); order(changed).putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "计划仓");
    var saved = save(changed, sales);
    assertEquals("5", order(saved).path("approvalDraft").path("warehouseId").asText());
    assertEquals(100, order(saved).path("total").asInt()); assertEquals(0, order(saved).path("paid").asInt());
    assertTrue(history.isEmpty());
  }

  @Test void warehouseSelectionIsLockedEvenForLegacyApprovedOrdersAndAssociationOnlyEdits() {
    var changed = draft(); order(changed).putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "计划仓");
    assertThrows(ApiException.class, () -> save(changed, admin));
    order(stored).remove("customerId");
    var associated = draft(); order(associated).put("customerId", "c1");
    order(associated).putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "计划仓");
    assertThrows(ApiException.class, () -> save(associated, admin));
    verify(db, never()).state(any(JsonNode.class));
  }

  @Test void rawDraftSaveRejectsInvalidWarehouseIdsAndAcceptsExplicitEmptySelection() {
    order(stored).put("status", "草稿");
    for (String id : List.of("0", "-1", "warehouse", "1.1", "9223372036854775808")) {
      var changed = draft(); order(changed).putObject("approvalDraft").put("warehouseId", id);
      assertThrows(ApiException.class, () -> save(changed, sales));
    }
    var empty = draft(); order(empty).putObject("approvalDraft").put("warehouseId", "").put("warehouseName", "");
    assertEquals("草稿", order(save(empty, sales)).path("status").asText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "APPROVED"})
  void feishuSubmissionDetailsStayLockedWhileNotesAndAttachmentsRemainEditable(String approvalStatus) {
    order(stored).put("status", approvalStatus.equals("APPROVED") ? "已批准" : "审批中");
    order(stored).putObject("approvalDraft").put("recipientName", "原收货人").put("recipientPhone", "13000000000")
        .put("recipientAddress", "原地址").put("receiptDate", "2026-11-23").put("receiptCategory", "销售收入");
    when(db.rows("SELECT order_id,status FROM feishu_order_approvals"))
        .thenReturn(List.of(Map.of("order_id", "o1", "status", approvalStatus)));
    for (String field : List.of("recipientName", "recipientPhone", "recipientAddress", "receiptDate", "receiptCategory")) {
      var changed = draft();
      ((ObjectNode) order(changed).path("approvalDraft")).put(field, "另一份提交资料");
      assertTrue(assertThrows(ApiException.class, () -> save(changed, admin)).getMessage().contains("提交内容"));
    }
    verify(db, never()).state(any(JsonNode.class));
    var permitted = draft(); order(permitted).put("note", "提交后的补充备注");
    order(permitted).putArray("attachments").addObject().put("name", "补充附件.png").put("url", "/api/assets/fixture");
    var saved = save(permitted, sales);
    assertEquals("提交后的补充备注", order(saved).path("note").asText());
    assertEquals(1, order(saved).path("attachments").size());
    assertEquals("原地址", order(saved).path("approvalDraft").path("recipientAddress").asText());
  }

  @Test void catalogCannotBeOverwrittenThroughBusinessSave() {
    var next=draft();((ObjectNode)next.path("products").get(0)).put("name","伪造商品");
    var error=assertThrows(ApiException.class,()->save(next,admin));
    assertTrue(error.getMessage().contains("聚水潭"));
    verify(db,never()).state(any(JsonNode.class));
  }
  @Test void approvedAmountCannotBeChangedBySalesEvenWithMatchingItems() {
    var next = draft(); order(next).put("total", 200);
    ((ObjectNode) order(next).path("items").get(0)).put("price", 200);
    assertThrows(ApiException.class, () -> save(next, sales));
    verify(db, never()).state(any(JsonNode.class));
  }
  @Test void approvedStatusCannotBeDowngradedBySales() {
    var next = draft(); order(next).put("status", "草稿");
    assertThrows(ApiException.class, () -> save(next, sales));
  }
  @Test void salesCanEditApprovedNotesWithoutReapproval() {
    var next = draft(); order(next).put("note", "补充说明");
    assertEquals("补充说明", save(next, sales).path("orders").get(0).path("note").asText());
  }
  @Test void administratorCannotEditUnpaidApprovedCoreFieldsOutsideFeishu() {
    var next = draft(); order(next).put("total", 200);
    ((ObjectNode) order(next).path("items").get(0)).put("price", 200);
    assertTrue(assertThrows(ApiException.class, () -> save(next, admin)).getMessage().contains("飞书"));
  }
  @Test void subCentPaymentIsRejectedWithoutCreatingReceipt() {
    var next = draft(); order(next).put("paid", 0.004).put("method", "银行转账");
    assertThrows(ApiException.class, () -> save(next, admin));
    verify(db, never()).receipt(any());
  }
  @Test void clientCannotForgeOrderTotal() {
    var next = draft(); order(next).put("total", 200);
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void clientCannotUseMissingProductOrNegativePrice() {
    var next = draft(); ((ObjectNode) order(next).path("items").get(0)).put("product", "missing");
    assertThrows(ApiException.class, () -> save(next, admin));
    ((ObjectNode) order(next).path("items").get(0)).put("product", "p1").put("price", -100);
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void duplicateCustomerNamesAreRejected() {
    var next = draft(); ((ArrayNode) next.path("customers")).addObject().put("id", "c2").put("name", "客户甲");
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void renameKeepsCustomerIdAndDoesNotRewriteIdentifiedReceipts() throws Exception {
    history.add(json("""
      {"id":"r1","order":"o1","customerId":"c1","customer":"客户甲","amount":10,"type":"预收款订单","method":"银行转账"}
      """));
    var next = draft(); ((ObjectNode) next.path("customers").get(0)).put("name", "客户乙");
    var saved = save(next, sales);
    assertEquals("c1", order(saved).path("customerId").asText());
    assertEquals("客户乙", order(saved).path("customer").asText());
    assertEquals(10, saved.path("customers").get(0).path("balance").asInt());
    assertEquals("客户乙", service.receipts().getFirst().path("customer").asText());
    verify(db, never()).receipt(any());
  }
  @Test void legacyReferencesAreUpgradedOnce() throws Exception {
    order(stored).remove("customerId");
    history.add(json("""
      {"id":"r1","order":"o1","customer":"客户甲","amount":10,"type":"预收款订单","method":"银行转账"}
      """));
    var next = draft(); ((ObjectNode) next.path("customers").get(0)).put("name", "客户乙");
    save(next, sales);
    var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).receipt(capture.capture());
    assertEquals("c1", capture.getValue().path("customerId").asText());
  }
  @Test void existingPaidCoreFieldsRemainImmutableForAdministrator() {
    order(stored).put("paid", 10);
    var next = draft(); order(next).put("total", 200);
    ((ObjectNode) order(next).path("items").get(0)).put("price", 200);
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void onlyNewReceiptIsWrittenOnPayment() throws Exception {
    history.add(json("""
      {"id":"r1","order":"o1","customerId":"c1","customer":"客户甲","amount":10,"type":"销售订单","method":"银行转账"}
      """));
    order(stored).put("paid", 10);
    var next = draft(); order(next).put("paid", 20).put("method", "银行转账");
    save(next, admin);
    var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).receipt(capture.capture());
    assertEquals(10, capture.getValue().path("amount").asInt());
    assertNotEquals("r1", capture.getValue().path("id").asText());
  }
  @Test void ambiguousLegacyOrdersRemainReadableButCannotReceivePayments() {
    ((ArrayNode) stored.path("customers")).addObject().put("id", "c2").put("name", "客户甲").put("balance", 0);
    order(stored).remove("customerId");
    assertEquals("客户甲", order(service.state()).path("customer").asText());
    assertTrue(order(service.state()).path("customerId").asText().isBlank());
    save(draft(), sales);
    var next = draft(); order(next).put("paid", 1).put("method", "银行转账");
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void orphanLegacyOrderRequiresExplicitSelectionBeforePayment() {
    order(stored).remove("customerId"); order(stored).put("customer", "旧系统客户");
    save(draft(), sales); // Other business operations must remain available.
    var next = draft(); order(next).put("customerId", "c1");
    var saved = save(next, admin);
    assertEquals("c1", order(saved).path("customerId").asText());
    assertEquals("客户甲", order(saved).path("customer").asText());
  }
  @Test void paidLegacyAssociationCanBeRepairedExplicitlyWithoutChangingMoney() throws Exception {
    order(stored).remove("customerId"); order(stored).put("customer", "旧系统客户").put("paid", 10);
    history.add(json("""
      {"id":"r1","order":"o1","customer":"旧系统客户","amount":10,"type":"销售订单","method":"银行转账"}
      """));
    var next = draft(); order(next).put("customerId", "c1");
    save(next, admin);
    var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).receipt(capture.capture());assertEquals("c1", capture.getValue().path("customerId").asText());
  }

  @Test void amendmentRefundLedgerDoesNotBlockOrdinarySaveOrChangePrepaymentBalance() throws Exception {
    history.add(json("{\"id\":\"diff-1\",\"order\":\"o1\",\"customerId\":\"c1\",\"amount\":-5,\"type\":\"订单变更\",\"amendmentId\":\"BG-1\",\"method\":\"银行转账\"}"));
    var result=save(draft(),admin);
    assertEquals(0,result.path("customers").get(0).path("balance").asInt());
    assertEquals(100,result.path("orders").get(0).path("total").asInt());
  }
  @Test void ordinaryNegativeReceiptIsStillRejected() throws Exception {
    history.add(json("{\"id\":\"bad\",\"order\":\"o1\",\"customerId\":\"c1\",\"amount\":-5,\"type\":\"销售订单\",\"method\":\"银行转账\"}"));
    assertThrows(ApiException.class,()->save(draft(),admin));
  }

  @Test void restoreRejectsOlderShipmentAndMissingDifferenceReceiptBeforeWriting() throws Exception {
    var shipment=mapper.createObjectNode().put("orderId","o1").put("soId","CRM-new");
    shipment.set("snapshot",order(stored).deepCopy());
    shipment.putObject("amendment").putObject("settlement").put("id","diff-1");
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    when(db.parse(anyString())).thenAnswer(i->mapper.readTree((String)i.getArgument(0)));
    var backup=mapper.createObjectNode().put("version",1);backup.set("state",draft());backup.putArray("receipts");
    backup.putArray("jstShipments").add(shipment.deepCopy().put("soId","CRM-old"));
    assertThrows(ApiException.class,()->service.restore(backup,admin));
    backup.putArray("jstShipments").add(shipment);
    assertThrows(ApiException.class,()->service.restore(backup,admin));
    verify(db,never()).state(any(JsonNode.class));verify(db,never()).receipt(any());
  }
  @Test void effectiveAndPendingShipmentProductsCannotBeDeleted() throws Exception {
    ((ArrayNode)stored.path("products")).addObject().put("id","p2").put("name","变更新商品");
    var shipment=json("{\"orderId\":\"o1\",\"effectiveOrder\":{\"items\":[{\"product\":\"p2\"}]}}");
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    when(db.parse(anyString())).thenAnswer(i->mapper.readTree((String)i.getArgument(0)));
    ((ObjectNode)shipment).set("snapshot",order(stored).deepCopy());
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    var next=draft();((ArrayNode)next.path("products")).remove(1);
    var error=assertThrows(ApiException.class,()->save(next,admin));
    assertEquals("商品资料由聚水潭管理，请使用同步商品，不能在CRM新增、编辑或删除",error.getMessage());
  }

  @Test void restoreCannotDropExternallyConfirmedChangeHistory() throws Exception {
    var shipment=mapper.createObjectNode().put("orderId","o1").put("soId","CRM-same");
    shipment.set("snapshot",order(stored).deepCopy());
    shipment.putArray("externalChanges").addObject().put("id","EXT-1").put("status","CONFIRMED");
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    when(db.parse(anyString())).thenAnswer(i->mapper.readTree((String)i.getArgument(0)));
    var backup=mapper.createObjectNode().put("version",1);backup.set("state",draft());backup.putArray("receipts");
    var old=shipment.deepCopy();old.remove("externalChanges");backup.putArray("jstShipments").add(old);
    var error=assertThrows(ApiException.class,()->service.restore(backup,admin));assertTrue(error.getMessage().contains("备份早于现有聚水潭变更"));
    verify(db,never()).state(any(JsonNode.class));verify(db,never()).receipt(any());
  }

  @Test void restoreCannotEraseTheDurableExternalJournal() throws Exception {
    var shipment=mapper.createObjectNode().put("orderId","o1").put("soId","CRM-same");shipment.set("snapshot",order(stored).deepCopy());
    shipment.putArray("externalTimeline").addObject().put("id","log-1").put("type","DETECTED");
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    when(db.parse(anyString())).thenAnswer(i->mapper.readTree((String)i.getArgument(0)));
    var backup=mapper.createObjectNode().put("version",1);backup.set("state",draft());backup.putArray("receipts");
    var old=shipment.deepCopy();old.remove("externalTimeline");backup.putArray("jstShipments").add(old);
    assertThrows(ApiException.class,()->service.restore(backup,admin));verify(db,never()).state(any(JsonNode.class));
  }

  @Test void eachOrderEditRecordsExactBeforeAfterAndActor() {
    var next=draft(); order(next).put("note", "新的备注"); save(next, admin);
    var capture=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).audit(eq("admin"), eq("修改订单"), capture.capture());
    var detail=capture.getValue(); assertEquals("o1",detail.path("orderId").asText());
    assertFalse(detail.path("before").has("note")); assertEquals("新的备注",detail.path("after").path("note").asText());
    assertEquals("note",detail.path("changes").get(0).path("field").asText());
    assertEquals(2,detail.path("revisionAfter").asInt());
  }
  @Test void unchangedSaveDoesNotInventAnOrderModification() {
    save(draft(),admin); verify(db,never()).audit(anyString(),eq("修改订单"),any(JsonNode.class));
  }
  @Test void paymentHasBothOrderDifferenceAndReceiptJournal() {
    var next=draft();order(next).put("paid",25);order(next).put("method","银行转账");save(next,admin);
    verify(db).audit(eq("admin"),eq("修改订单"),any(JsonNode.class));
    var capture=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).audit(eq("admin"),eq("新增收款流水"),capture.capture());
    assertEquals("o1",capture.getValue().path("orderId").asText());
    assertEquals(25,capture.getValue().path("after").path("amount").asInt());
  }
  @Test void invalidModificationCannotWriteSuccessJournal() {
    var next=draft();order(next).put("total",-1);assertThrows(ApiException.class,()->save(next,admin));
    verify(db,never()).audit(anyString(),anyString(),any(JsonNode.class));
  }
  @Test void orderHistoryRequiresBusinessReadPermission() {
    assertThrows(ApiException.class,()->service.orderHistory("o1",new Actor("blocked","待授权",Set.of())));
  }

  @Test void editingAndCreationInOneSaveEachHaveTheirOwnJournal() {
    order(stored).put("status","草稿");var next=draft();order(next).put("note","草稿补充说明");
    var created=order(next).deepCopy();created.put("id","o2").put("status","草稿");((ArrayNode)next.path("orders")).add(created);
    save(next,admin);
    var edited=org.mockito.ArgumentCaptor.forClass(JsonNode.class);var added=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).audit(eq("admin"),eq("修改订单"),edited.capture());verify(db).audit(eq("admin"),eq("新建订单"),added.capture());
    assertEquals("草稿",edited.getValue().path("before").path("status").asText());
    assertEquals("草稿",edited.getValue().path("after").path("status").asText());
    assertEquals("草稿补充说明",edited.getValue().path("after").path("note").asText());
    assertEquals("o2",added.getValue().path("orderId").asText());assertTrue(added.getValue().path("before").isNull());
  }

  @ParameterizedTest @ValueSource(strings={"销售订单", "海外提货单", "预收款订单"})
  void noDefinitionOrOptionalFlagCanReenableOrdinaryLocalApproval(String type) {
    order(stored).put("type", type).put("status", "草稿");
    for (Actor actor : List.of(admin, sales)) {
      for (String status : List.of("已批准", "审批中")) {
        var next = draft(); order(next).put("status", status);
        assertTrue(assertThrows(ApiException.class, () -> save(next, actor)).getMessage().contains("飞书"));
      }
    }
    verify(db, never()).state(any(JsonNode.class));
  }

  @ParameterizedTest @ValueSource(strings={"销售订单", "海外提货单", "预收款订单"})
  void ordinaryBackupRestoreCannotApproveAnExistingDraftWithoutAFeishuDefinition(String type) {
    order(stored).put("type", type).put("status", "草稿");
    var next = draft(); order(next).put("status", "已批准");
    var backup = mapper.createObjectNode().put("version", 1);
    backup.set("state", next); backup.putArray("receipts");
    assertTrue(assertThrows(ApiException.class, () -> service.restore(backup, admin)).getMessage().contains("飞书"));
    verify(db, never()).state(any(JsonNode.class)); verify(db, never()).receipt(any());
  }

  @Test void settlementConfirmationStillUsesItsExistingPermissionRule() {
    order(stored).put("source", JushuitanDocumentService.SETTLEMENT_SOURCE).put("status", "审批中");
    order(stored).putObject("settlement").put("cycle", "month").put("periodStart", "2026-10-01").put("periodEnd", "2026-10-31");
    order(stored).putArray("items");
    var next = draft(); order(next).put("status", "已批准");
    assertThrows(ApiException.class, () -> save(next, sales));
    assertEquals("已批准", order(save(next, admin)).path("status").asText());
  }

  @Test void offlineCustomerMigrationCannotBeUsedToApproveAnOrdinaryOrder() {
    order(stored).put("status", "草稿");
    var next = draft(); order(next).put("status", "已批准");
    var body = mapper.createObjectNode().put("revision", stored.path("revision").asLong()); body.set("data", next);
    var operator = new Actor("ops", "管理员", Set.of("business.read", "business.write", "order.approve", "payment.write", "system.manage"));
    assertTrue(assertThrows(ApiException.class, () -> service.saveLegacyMigration(body, operator)).getMessage().contains("飞书"));
    verify(db, never()).state(any(JsonNode.class));
  }

  @Test void latestBackupCanRestoreTheDerivedAssociationOfAnApprovedHistoricalOrder() {
    order(stored).remove("customerId");
    var assets = mock(AssetService.class); when(assets.backup(any())).thenReturn(Map.of());
    service = new BusinessService(db, assets);
    var backup = mapper.valueToTree(service.backup());
    assertEquals("c1", order(backup.path("state")).path("customerId").asText());
    assertFalse(order(stored).has("customerId"));
    service.restore(backup, admin);
    assertEquals("c1", order(stored).path("customerId").asText());
    assertEquals("已批准", order(stored).path("status").asText());
    verify(db, never()).receipt(any());
  }

  @Test void latestBackupWithActiveReceiptCanRestoreTheSameDerivedHistoricalAssociation() throws Exception {
    order(stored).remove("customerId");
    var active = json("{\"id\":\"receipt-1\",\"status\":\"PENDING\",\"allocations\":[{\"orderId\":\"o1\",\"amount\":1}]}");
    when(db.parse(anyString())).thenAnswer(call -> mapper.readTree(call.getArgument(0, String.class)));
    when(db.rows("SELECT payload FROM feishu_receipt_approvals ORDER BY id"))
        .thenReturn(List.of(Map.of("payload", active.toString())));
    when(db.rows(argThat(sql -> sql != null && sql.contains("feishu_receipt_approvals") && sql.contains("WHERE status IN"))))
        .thenReturn(List.of(Map.of("payload", active.toString())));
    var assets = mock(AssetService.class); when(assets.backup(any())).thenReturn(Map.of());
    service = new BusinessService(db, assets);
    var backup = mapper.valueToTree(service.backup());
    service.restore(backup, admin);
    assertEquals("c1", order(stored).path("customerId").asText());
    assertEquals("已批准", order(stored).path("status").asText());
    assertEquals(0, order(stored).path("paid").asInt());
    verify(db, never()).receipt(any());
  }

  @ParameterizedTest @ValueSource(strings={"orphan", "ambiguous", "other-customer"})
  void restoreCannotChooseAnotherAssociationForAnApprovedHistoricalOrder(String kind) {
    order(stored).remove("customerId");
    if (kind.equals("orphan")) order(stored).put("customer", "未匹配旧客户");
    else ((ArrayNode) stored.path("customers")).addObject().put("id", "c2")
        .put("name", kind.equals("ambiguous") ? "客户甲" : "客户乙").put("balance", 0);
    var next = draft(); order(next).put("customerId", "c1");
    if (kind.equals("other-customer")) order(next).put("customerId", "c2").put("customer", "客户乙");
    var backup = mapper.createObjectNode().put("version", 1); backup.set("state", next); backup.putArray("receipts");
    assertTrue(assertThrows(ApiException.class, () -> service.restore(backup, admin)).getMessage().contains("飞书"));
    verify(db, never()).state(any(JsonNode.class));
  }

  @Test void prepaymentProductsAreOptionalAndTheirTotalIsIndependent() {
    order(stored).put("type", "预收款订单").put("status", "草稿");
    var next = draft(); order(next).put("total", 50);
    assertEquals(50, order(save(next, sales)).path("total").asInt());
    assertEquals(1, order(stored).path("items").size());
    var empty = draft(); order(empty).putArray("items");
    assertTrue(order(save(empty, sales)).path("items").isEmpty());
  }
  @Test void suppliedPrepaymentProductsStillValidateQuantityPrecisionAndCatalogOwnership() {
    order(stored).put("type", "预收款订单").put("status", "草稿");
    for (String quantity : List.of("0", "-1", "0.1234567", "1000000001")) {
      var next = draft(); ((ObjectNode) order(next).path("items").get(0)).put("qty", quantity);
      assertThrows(ApiException.class, () -> save(next, sales));
    }
    var invalidPrice = draft(); ((ObjectNode) order(invalidPrice).path("items").get(0)).put("price", "1.001");
    assertThrows(ApiException.class, () -> save(invalidPrice, sales));
    var unknown = draft(); ((ObjectNode) order(unknown).path("items").get(0)).put("product", "missing");
    assertThrows(ApiException.class, () -> save(unknown, sales));
  }
  @Test void safeSoftDeletionRetainsTheOrderAndCannotBeReversedThroughSaveOrRestore() {
    order(stored).put("status", "草稿");
    when(db.rows("SELECT id FROM receipts WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.order'))=? LIMIT 1", "o1")).thenReturn(List.of());
    when(db.rows("SELECT status,instance_code FROM feishu_order_approvals WHERE order_id=? FOR UPDATE", "o1")).thenReturn(List.of());
    var deleted = service.deleteOrder("o1", sales);
    String at = order(deleted).path("deletedAt").asText();
    assertFalse(at.isBlank()); assertEquals("已取消", order(deleted).path("status").asText());
    assertEquals(at, order(service.deleteOrder("o1", sales)).path("deletedAt").asText());
    var changed = draft(); order(changed).remove("deletedAt"); order(changed).put("status", "草稿");
    assertThrows(ApiException.class, () -> save(changed, admin));
    var backup = mapper.createObjectNode().put("version", 1); backup.set("state", changed); backup.putArray("receipts");
    assertThrows(ApiException.class, () -> service.restore(backup, admin));
  }
  @Test void deletionRejectsApprovedOrFinanciallyReferencedOrders() {
    assertThrows(ApiException.class, () -> service.deleteOrder("o1", admin));
    order(stored).put("status", "草稿");
    when(db.rows("SELECT id FROM receipts WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.order'))=? LIMIT 1", "o1"))
        .thenReturn(List.of(Map.of("id", "historical-zero-net")));
    assertThrows(ApiException.class, () -> service.deleteOrder("o1", admin));
  }
  @Test void stateCannotForgeCancellationOrDeletionMarkers() {
    var canceled = draft(); order(canceled).put("status", "已取消");
    assertThrows(ApiException.class, () -> save(canceled, admin));
    var deleted = draft(); order(deleted).put("deletedAt", "2026-11-23T00:00:00Z");
    assertThrows(ApiException.class, () -> save(deleted, admin));
  }
  @ParameterizedTest @ValueSource(strings={"canceled", "deleted"})
  void archivedOrdersKeepTheirRecordedNameWhileCustomerRenameRemainsAvailable(String kind) {
    order(stored).put("status", "已取消");
    if (kind.equals("deleted")) order(stored).put("deletedAt", "2026-11-23T00:00:00Z");
    var next = draft(); ((ObjectNode) next.path("customers").get(0)).put("name", "客户新名称");
    var result = save(next, sales);
    assertEquals("客户新名称", result.path("customers").get(0).path("name").asText());
    assertEquals("客户甲", order(result).path("customer").asText());
    assertEquals("c1", order(result).path("customerId").asText());
    assertEquals("已取消", order(result).path("status").asText());
    assertEquals(kind.equals("deleted"), order(result).hasNonNull("deletedAt"));
  }
  @Test void newReceiptStoresAProductSnapshotWithoutChangingMoney() {
    var next = draft(); order(next).put("paid", 25).put("method", "银行转账"); save(next, admin);
    var receipt = history.get(0);
    assertEquals("snapshot", receipt.path("itemsSource").asText());
    assertEquals("产品", receipt.path("items").get(0).path("name").asText());
    assertEquals(1, receipt.path("items").get(0).path("qty").asInt());
    assertEquals(25, receipt.path("amount").asInt());
    ((ObjectNode) stored.path("products").get(0)).put("name", "修改后的商品");
    assertEquals("产品", service.receipts().get(0).path("items").get(0).path("name").asText());
  }
  @Test void legacyReceiptsAreEnrichedOnlyForDisplayAndBackupKeepsTheirOriginalShape() {
    history.add(mapper.createObjectNode().put("id", "r1").put("order", "o1").put("customerId", "c1").put("amount", -10)
        .put("type", "订单变更").put("amendmentId", "refund-1").put("method", "银行转账"));
    var view = service.receipts().get(0);
    assertEquals("order", view.path("itemsSource").asText()); assertEquals(-10, view.path("amount").asInt());
    assertFalse(history.get(0).has("items"));
    var assets = mock(AssetService.class); when(assets.backup(any())).thenReturn(Map.of());
    service = new BusinessService(db, assets);
    var backup = mapper.valueToTree(service.backup());
    assertFalse(backup.path("receipts").get(0).has("items"));
    assertFalse(backup.path("receipts").get(0).has("itemsSource"));
    verify(db, never()).receipt(any());
  }
  @Test void explicitDerivedReceiptItemsNeverBecomeAnInventedHistoricalSnapshot() {
    var receipt = mapper.createObjectNode().put("amount", 10).put("itemsSource", "order"); receipt.putArray("items");
    assertEquals("order", BusinessService.receiptItems(receipt, order(stored), Map.of()).path("itemsSource").asText());
    assertEquals("unknown", BusinessService.receiptItems(receipt, null, Map.of()).path("itemsSource").asText());
    receipt.put("itemsSource", "unknown");
    assertEquals("unknown", BusinessService.receiptItems(receipt, null, Map.of()).path("itemsSource").asText());
  }
  @Test void missingCatalogKeepsAvailableLineLabelsAndExplicitEmptySnapshotsStayEmpty() {
    var order = mapper.createObjectNode(); order.putArray("items").addObject().put("product", "old").put("name", "历史行名称")
        .put("sku", "OLD-SKU").put("model", "历史规格").put("qty", 2).put("price", 3);
    var result = BusinessService.receiptItems(mapper.createObjectNode(), order, Map.of());
    assertEquals("历史行名称", result.path("items").get(0).path("name").asText());
    assertEquals("OLD-SKU", result.path("items").get(0).path("sku").asText());
    var empty = mapper.createObjectNode(); empty.putArray("items");
    assertTrue(BusinessService.receiptItems(empty, order, Map.of()).path("items").isEmpty());
    assertEquals("snapshot", BusinessService.receiptItems(empty, order, Map.of()).path("itemsSource").asText());
  }

}
