package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyCustomerMigrationServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Actor ops = new Actor("旧客户迁移", "管理员", Set.of("business.read", "business.write", "payment.write", "system.manage", "order.approve"));
  private CrmRepository db;
  private BusinessService business;
  private LegacyCustomerMigrationService service;
  private ObjectNode stored;
  private List<JsonNode> receipts;

  @BeforeEach void setup() throws Exception {
    stored = (ObjectNode) mapper.readTree("""
      {"revision":7,"customers":[{"id":"c1","name":"已有客户","contact":"现有联系人","phone":"13800000000","balance":0}],
      "products":[{"id":"p1","name":"产品","source":"jushuitan","enabled":true}],
      "orders":[{"id":"o1","customerId":"c1","customer":"已有客户","type":"销售订单","status":"已批准","total":1000,"paid":0,
      "items":[{"product":"p1","qty":1,"price":1000}]}]}
      """);
    receipts = new ArrayList<>(); db = mock(CrmRepository.class);
    when(db.state(anyBoolean())).thenAnswer(c -> stored.deepCopy());
    when(db.object()).thenAnswer(c -> mapper.createObjectNode());
    when(db.jdbc()).thenReturn(mock(org.springframework.jdbc.core.JdbcTemplate.class));
    when(db.documents(eq("receipts"), anyBoolean(), eq(0))).thenAnswer(c -> receipts.stream().map(JsonNode::deepCopy).toList());
    doAnswer(c -> { stored = ((JsonNode) c.getArgument(0)).deepCopy(); return null; }).when(db).state(any(JsonNode.class));
    doAnswer(c -> { receipts.add(((JsonNode) c.getArgument(0)).deepCopy()); return null; }).when(db).receipt(any());
    business = new BusinessService(db, mock(AssetService.class));
    service = new LegacyCustomerMigrationService(db, business, mapper);
  }

  private ObjectNode body() {
    var body = mapper.createObjectNode();
    body.putObject("source").put("sha256", "a".repeat(64)).put("fileName", "旧客户.xlsx").put("sheet", "客户信息");
    body.putArray("rows"); return body;
  }

  private ObjectNode row(ObjectNode body, int number, String name, String id, String opening) {
    var row = ((ArrayNode) body.path("rows")).addObject().put("rowNumber", number).put("legacyId", id);
    row.putObject("customer").put("name", name).put("owner", "").put("contact", "").put("phone", "");
    var raw = row.putObject("raw").put("客户名称", name).put("ID", id).putNull("负责人");
    if (opening == null) raw.putNull("预收款金额"); else raw.put("预收款金额", opening);
    if (opening == null) row.putNull("openingBalance"); else row.put("openingBalance", new BigDecimal(opening));
    return row;
  }

  private ObjectNode decide(ObjectNode body) {
    var preview = service.preview(body, ops); body.put("revision", preview.path("revision").asLong());
    ArrayNode decisions = body.putArray("decisions");
    for (var row : preview.path("rows")) {
      var action = decisions.addObject().put("rowNumber", row.path("rowNumber").asInt())
          .put("action", row.path("status").asText().equals("ALREADY_IMPORTED") ? "SKIP" : row.path("status").asText());
      if (row.has("customerId")) action.put("customerId", row.path("customerId").asText());
    }
    return body;
  }

  private void migrateExisting(String amount) { var body = body(); row(body, 2, "已有客户", "0012345678901234", amount); service.commit(decide(body), ops); }
  private ObjectNode customer() { return (ObjectNode) stored.path("customers").get(0); }
  private JsonNode save(ObjectNode next) { var body = mapper.createObjectNode().put("revision", stored.path("revision").asLong()); body.set("data", next); return business.save(body, ops); }

  @Test void previewWritesNothingAndDoesNotInventRequiredHistoricalFields() {
    var body = body(); row(body, 2, "新客户", "0012345678901234", null);
    var result = service.preview(body, ops);
    assertEquals(1, result.path("summary").path("created").asInt());
    assertEquals("", result.path("rows").get(0).path("customer").path("phone").asText());
    verify(db, never()).state(any(JsonNode.class)); verify(db, never()).receipt(any());
  }

  @Test void migrationPreservesZeroVersusBlankAndKeepsIdentifiersAsText() {
    var body = body(); row(body, 2, "余额未知", "0012345678901234", null); row(body, 3, "零余额", "0012345678901235", "0");
    service.commit(decide(body), ops);
    JsonNode blank = stored.path("customers").get(1), zero = stored.path("customers").get(2);
    assertFalse(blank.has("openingBalance")); assertTrue(zero.path("openingBalance").isNumber());
    assertEquals("0012345678901234", blank.path("legacySource").path("id").asText());
    assertTrue(blank.path("legacySource").path("fields").path("负责人").isNull());
    assertFalse(blank.has("externalLinks")); assertTrue(receipts.isEmpty());
  }

  @Test void exactNameLinkPreservesCoreOrdersAndProductsAndCreatesNoReceipt() {
    var before = stored.deepCopy(); migrateExisting("270.00");
    var after = customer().deepCopy(); after.remove(List.of("legacySource", "openingBalance", "balance"));
    var original = ((ObjectNode) before.path("customers").get(0)).deepCopy(); original.remove("balance");
    assertEquals(original, after); assertEquals(before.path("orders"), stored.path("orders"));
    assertEquals(before.path("products"), stored.path("products")); assertEquals(new BigDecimal("270.00"), customer().path("balance").decimalValue());
    assertEquals(new BigDecimal("270.00"), customer().path("openingBalance").decimalValue()); assertTrue(receipts.isEmpty());
  }

  @Test void replayAfterRenameDoesNotAddCreditOrWriteAnotherRevision() {
    migrateExisting("270"); var next = stored.deepCopy(); ((ObjectNode) next.path("customers").get(0)).put("name", "改名客户"); save(next);
    long revision = stored.path("revision").asLong(); var body = body(); row(body, 2, "已有客户", "0012345678901234", "270");
    var preview = service.preview(body, ops); assertEquals(1, preview.path("summary").path("alreadyImported").asInt());
    service.commit(decide(body), ops); assertEquals(revision, stored.path("revision").asLong());
    assertEquals(new BigDecimal("270.00"), customer().path("balance").decimalValue());
    assertEquals("改名客户", customer().path("name").asText()); assertTrue(receipts.isEmpty());
  }

  @Test void changedOpeningCreditOnReplayIsAConflict() {
    migrateExisting("270"); var body = body(); row(body, 2, "已有客户", "0012345678901234", "271");
    assertEquals(1, service.preview(body, ops).path("summary").path("invalid").asInt());
    assertThrows(ApiException.class, () -> service.commit(decide(body), ops));
  }

  @Test void currentStateSavePreservesOpeningAndConsumesItWithARealWriteoff() {
    migrateExisting("270"); save(stored.deepCopy()); assertEquals(270, customer().path("balance").asInt());
    var next = stored.deepCopy(); ((ObjectNode) next.path("orders").get(0)).put("paid", 100).put("method", "预收款核销"); save(next);
    assertEquals(new BigDecimal("170.00"), customer().path("balance").decimalValue());
    assertEquals(new BigDecimal("270.00"), customer().path("openingBalance").decimalValue());
    assertEquals(1, receipts.size()); assertEquals("预收款核销", receipts.get(0).path("method").asText());
    save(stored.deepCopy()); assertEquals(new BigDecimal("170.00"), customer().path("balance").decimalValue());
  }

  @Test void overdraftDoesNotWriteEitherCustomerOrReceipt() {
    migrateExisting("270"); var before = stored.deepCopy(); var next = stored.deepCopy();
    ((ObjectNode) next.path("orders").get(0)).put("paid", 271).put("method", "预收款核销");
    assertThrows(ApiException.class, () -> save(next)); assertEquals(before, stored); assertTrue(receipts.isEmpty());
  }

  @Test void genericStateSaveCannotAddAlterOrRemoveProtectedMigrationFields() {
    var next = stored.deepCopy(); ((ObjectNode) next.path("customers").get(0)).put("openingBalance", 999);
    assertThrows(ApiException.class, () -> save(next));
    migrateExisting("270");
    for (String field : List.of("openingBalance", "legacySource")) {
      var removed = stored.deepCopy(); ((ObjectNode) removed.path("customers").get(0)).remove(field);
      assertThrows(ApiException.class, () -> save(removed));
    }
    var changed = stored.deepCopy(); ((ObjectNode) changed.path("customers").get(0)).put("openingBalance", 271);
    assertThrows(ApiException.class, () -> save(changed));
    var modifiedSource = stored.deepCopy(); ((ObjectNode) modifiedSource.path("customers").get(0).path("legacySource")).put("id", "other");
    assertThrows(ApiException.class, () -> save(modifiedSource));
  }

  @Test void duplicateOldIdsAndNamesFailBeforeAnyWrite() {
    var body = body(); row(body, 2, "新客户", "0012345678901234", "10"); row(body, 3, "新客户", "0012345678901234", "10");
    assertEquals(2, service.preview(body, ops).path("summary").path("invalid").asInt());
    body.put("revision", 7); body.putArray("decisions"); assertThrows(ApiException.class, () -> service.commit(body, ops));
    verify(db, never()).state(any(JsonNode.class));
  }

  @Test void linkTargetAndRevisionMustMatchPreview() {
    var body = body(); row(body, 2, "已有客户", "0012345678901234", "270"); decide(body);
    ((ObjectNode) body.path("decisions").get(0)).put("customerId", "other"); assertThrows(ApiException.class, () -> service.commit(body, ops));
    decide(body); body.put("revision", 6); assertEquals(409, assertThrows(ApiException.class, () -> service.commit(body, ops)).status());
    verify(db, never()).state(any(JsonNode.class));
  }

  @Test void existingBalanceOrReceiptsRequireFinancialReviewBeforeAddingOpening() {
    customer().put("balance", 10); var body = body(); row(body, 2, "已有客户", "0012345678901234", "270");
    assertEquals(1, service.preview(body, ops).path("summary").path("invalid").asInt());
    customer().put("balance", "10.00");
    assertEquals(1, service.preview(body, ops).path("summary").path("invalid").asInt());
    customer().put("balance", 0); receipts.add(mapper.createObjectNode().put("customerId", "c1").put("amount", 10));
    assertEquals(1, service.preview(body, ops).path("summary").path("invalid").asInt());
  }

  @Test void importerRequiresFinancialAndSystemPermissions() {
    var actor = new Actor("普通销售", "销售", Set.of("business.read", "business.write"));
    var body = body(); row(body, 2, "新客户", "0012345678901234", null);
    assertThrows(ApiException.class, () -> service.preview(body, actor)); verify(db, never()).state(anyBoolean());
  }

  @ParameterizedTest @ValueSource(strings = {"-1", "0.001", "1000000000000"})
  void invalidOpeningAmountsCannotReachState(String amount) {
    var body = body(); row(body, 2, "新客户", "0012345678901234", amount);
    assertThrows(ApiException.class, () -> service.preview(body, ops)); verify(db, never()).state(any(JsonNode.class));
  }

  @Test void restoreRoundTripKeepsOpeningAndVerifiesLedgerReconciliation() {
    migrateExisting("270"); var backup = mapper.createObjectNode().put("version", 1); backup.set("state", stored.deepCopy()); backup.putArray("receipts");
    business.restore(backup, ops); assertEquals(270, customer().path("balance").asInt()); assertTrue(receipts.isEmpty());
    ((ObjectNode) backup.path("state").path("customers").get(0)).put("balance", 271);
    assertThrows(ApiException.class, () -> business.restore(backup, ops));
    ((ObjectNode) backup.path("state").path("customers").get(0)).put("balance", 270).put("openingBalance", "270");
    assertThrows(ApiException.class, () -> business.restore(backup, ops));
  }

  @Test void zeroOpeningUnreferencedCustomerCanStillBeDeletedNormally() {
    var body = body(); row(body, 2, "零余额新客户", "0012345678901234", "0"); service.commit(decide(body), ops);
    var next = stored.deepCopy(); ((ArrayNode) next.path("customers")).remove(1); save(next);
    assertEquals(1, stored.path("customers").size());
  }

  @Test void mappedIdentityNameAndMoneyMustAgreeWithOriginalColumns() {
    for (String field : List.of("ID", "客户名称", "预收款金额")) {
      var body = body(); var row = row(body, 2, "新客户", "0012345678901234", "270");
      ((ObjectNode) row.path("raw")).put(field, "other");
      assertThrows(ApiException.class, () -> service.preview(body, ops));
    }
    var blank = body(); var row = row(blank, 2, "新客户", "0012345678901234", null); row.put("openingBalance", 0);
    assertThrows(ApiException.class, () -> service.preview(blank, ops));
    verify(db, never()).state(any(JsonNode.class));
  }

  private ObjectNode backup() {
    var backup = mapper.createObjectNode().put("version", 1); backup.set("state", stored.deepCopy());
    ArrayNode saved = backup.putArray("receipts"); receipts.forEach(r -> saved.add(r.deepCopy())); return backup;
  }

  @Test void restoreCannotEraseOpeningOrRevertPostMigrationWriteoffs() {
    var beforeMigration = backup(); migrateExisting("270"); var beforeWriteoff = backup();
    assertThrows(ApiException.class, () -> business.restore(beforeMigration, ops));
    var next = stored.deepCopy(); ((ObjectNode) next.path("orders").get(0)).put("paid", 100).put("method", "预收款核销"); save(next);
    assertThrows(ApiException.class, () -> business.restore(beforeWriteoff, ops));
    for (String field : List.of("openingBalance", "legacySource")) {
      var altered = backup(); ((ObjectNode) altered.path("state").path("customers").get(0)).remove(field);
      assertThrows(ApiException.class, () -> business.restore(altered, ops));
    }
    var current = backup(); business.restore(current, ops); assertEquals(170, customer().path("balance").asInt());
  }

  @Test void restoreCannotRevertPaidOrderEvenIfItsWriteoffReceiptWasPreserved() {
    migrateExisting("270"); var next = stored.deepCopy();
    ((ObjectNode) next.path("orders").get(0)).put("paid", 100).put("method", "预收款核销"); save(next);
    var altered = backup(); ((ObjectNode) altered.path("state").path("orders").get(0)).put("paid", 0);
    assertThrows(ApiException.class, () -> business.restore(altered, ops));
  }

  @Test void completeBackupCanRestoreOpeningAndWriteoffToAnEmptyCustomerSet() {
    migrateExisting("270"); var next = stored.deepCopy();
    ((ObjectNode) next.path("orders").get(0)).put("paid", 100).put("method", "预收款核销"); save(next);
    var backup = backup(); stored.putArray("customers"); stored.putArray("orders"); receipts.clear();
    business.restore(backup, ops); assertEquals(170, customer().path("balance").asInt());
    assertEquals(1, receipts.size()); assertEquals(270, customer().path("openingBalance").asInt());
  }
}
