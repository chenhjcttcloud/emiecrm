package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerImportServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Actor writer = new Actor("sales", "销售", Set.of("business.read", "business.write"));
  private CrmRepository db;
  private BusinessService business;
  private CustomerImportService service;
  private ObjectNode stored;

  @BeforeEach void setup() throws Exception {
    stored = (ObjectNode) mapper.readTree("""
        {"revision":7,"customers":[
          {"id":"c1","name":"已有客户","entity":"原主体","owner":"原负责人","phone":"13800000000","balance":86.50},
          {"id":"c2","name":"同电话客户","phone":"13800000000","balance":0}
        ],"products":[{"id":"p1","name":"已有商品"}],"orders":[{"id":"o1","customerId":"c1","total":100,"paid":50}]}
        """);
    db = mock(CrmRepository.class); business = mock(BusinessService.class);
    when(db.state(anyBoolean())).thenAnswer(call -> stored.deepCopy());
    when(business.save(any(), eq(writer))).thenAnswer(call -> {
      stored = ((JsonNode) call.getArgument(0)).path("data").deepCopy();
      stored.put("revision", stored.path("revision").asLong() + 1);
      return stored.deepCopy();
    });
    service = new CustomerImportService(db, business, mapper);
  }

  private ObjectNode body() {
    ObjectNode body = mapper.createObjectNode().put("environment", "production").put("customerType", "offline");
    body.putArray("rows"); return body;
  }

  private ObjectNode addRow(ObjectNode body, int row, String name, String externalId) {
    return ((ArrayNode) body.path("rows")).addObject().put("rowNumber", row).put("name", name)
        .put("externalId", externalId).put("entity", "新增主体").put("owner", "销售甲").put("status", "达成合作")
        .put("channel", "线下").put("contact", "联系人").put("phone", "13900000001");
  }

  private void decision(ObjectNode body, int row, String action, String customerId) {
    body.put("revision", 7);
    ArrayNode decisions = body.has("decisions") ? (ArrayNode) body.path("decisions") : body.putArray("decisions");
    ObjectNode decision = decisions.addObject().put("rowNumber", row).put("action", action);
    if (customerId != null) decision.put("customerId", customerId);
  }

  private ObjectNode existing() { return (ObjectNode) stored.path("customers").get(0); }

  private void existingLink(String environment, String type, String id) {
    ArrayNode links = existing().has("externalLinks") ? (ArrayNode) existing().path("externalLinks") : existing().putArray("externalLinks");
    links.addObject().put("system", "jushuitan").put("environment", environment).put("type", type).put("id", id).put("name", "外部旧名称");
  }

  @Test void previewDoesNotWriteAndSharedPhoneDoesNotMatchCustomers() {
    ObjectNode body = body(); addRow(body, 2, "新增客户", "0000123").put("phone", "13800000000");
    JsonNode preview = service.preview(body, writer);
    assertEquals(7, preview.path("revision").asLong());
    assertEquals("NEW", preview.path("rows").get(0).path("status").asText());
    assertEquals("0000123", preview.path("rows").get(0).path("customer").path("externalId").asText());
    assertTrue(preview.path("rows").get(0).path("matches").isEmpty());
    verifyNoInteractions(business); verify(db, never()).state(any(JsonNode.class));
  }

  @Test void defaultsFillOnlyEmptyFields() {
    ObjectNode body = body(); addRow(body, 2, "新增客户", "x1").put("entity", "").put("owner", "文件负责人");
    body.putObject("defaults").put("entity", "默认主体").put("owner", "默认负责人");
    JsonNode row = service.preview(body, writer).path("rows").get(0).path("customer");
    assertEquals("默认主体", row.path("entity").asText()); assertEquals("文件负责人", row.path("owner").asText());
  }

  @Test void repeatedExternalIdInvalidatesAllAffectedRowsAndCannotBeLinked() {
    ObjectNode body = body(); addRow(body, 2, "客户乙", "same"); addRow(body, 3, "客户丙", "same");
    JsonNode preview = service.preview(body, writer);
    assertEquals(2, preview.path("summary").path("invalid").asInt());
    for (JsonNode row : preview.path("rows")) { assertFalse(row.path("linkable").asBoolean()); assertFalse(row.path("createable").asBoolean()); }
    decision(body, 2, "LINK", "c1"); decision(body, 3, "SKIP", null);
    assertThrows(ApiException.class, () -> service.commit(body, writer)); verifyNoInteractions(business);
  }

  @Test void repeatedNewNameRequiresFileCorrectionAndDoesNotSilentlyMerge() {
    ObjectNode body = body(); addRow(body, 2, "重名客户", "x1"); addRow(body, 3, "重名客户", "x2");
    assertEquals(2, service.preview(body, writer).path("summary").path("invalid").asInt());
    decision(body, 2, "CREATE", null); decision(body, 3, "SKIP", null);
    assertThrows(ApiException.class, () -> service.commit(body, writer)); verifyNoInteractions(business);
  }

  @Test void newCustomerCreatesStableAssociationWithoutChangingOtherBusinessData() {
    ObjectNode before = stored.deepCopy(); ObjectNode body = body(); addRow(body, 2, "新增客户", "00000012");
    decision(body, 2, "CREATE", null);
    JsonNode result = service.commit(body, writer);
    assertEquals(1, result.path("summary").path("created").asInt());
    assertEquals(before.path("customers").get(0), stored.path("customers").get(0));
    assertEquals(before.path("orders"), stored.path("orders")); assertEquals(before.path("products"), stored.path("products"));
    JsonNode added = stored.path("customers").get(2);
    assertTrue(added.path("id").asText().startsWith("c")); assertEquals(0, added.path("balance").asInt());
    assertEquals("00000012", added.path("externalLinks").get(0).path("id").asText());
    assertEquals("production", added.path("externalLinks").get(0).path("environment").asText());
    verify(business, times(1)).save(any(), eq(writer));
  }

  @Test void sameNameNeedsExplicitLinkAndLinkDoesNotOverwriteExistingFieldsOrBalance() {
    ObjectNode before = stored.deepCopy(); ObjectNode body = body(); addRow(body, 2, "已有客户", "ext-1");
    JsonNode row = service.preview(body, writer).path("rows").get(0);
    assertEquals("REVIEW", row.path("status").asText()); assertEquals("c1", row.path("matches").get(0).path("id").asText());
    decision(body, 2, "CREATE", null);
    assertThrows(ApiException.class, () -> service.commit(body, writer));
    ((ObjectNode) body.path("decisions").get(0)).put("action", "LINK").put("customerId", "c1");
    assertEquals(1, service.commit(body, writer).path("summary").path("linked").asInt());
    ObjectNode after = existing().deepCopy(); after.remove("externalLinks");
    assertEquals(before.path("customers").get(0), after);
    assertEquals(before.path("orders"), stored.path("orders"));
  }

  @Test void sharedEntityWithDistinctNameCanBeExplicitlyCreatedAfterReview() {
    ObjectNode body = body(); addRow(body, 2, "同主体另一门店", "store-2").put("entity", "原主体");
    JsonNode row = service.preview(body, writer).path("rows").get(0);
    assertEquals("REVIEW", row.path("status").asText());
    assertTrue(row.path("createable").asBoolean());
    assertEquals("c1", row.path("matches").get(0).path("id").asText());
    decision(body, 2, "CREATE", null);
    assertEquals(1, service.commit(body, writer).path("summary").path("created").asInt());
    assertEquals("已有客户", existing().path("name").asText());
    assertEquals("同主体另一门店", stored.path("customers").get(2).path("name").asText());
  }

  @Test void largeSharedEntityPreviewsHaveBoundedCandidatesAndKeepExactIdentity() {
    ArrayNode customers = stored.putArray("customers");
    for (int i = 0; i < 2000; i++) customers.addObject().put("id", "store-" + i)
        .put("name", "已有门店" + i).put("entity", "共享主体").put("balance", 0);
    ((ObjectNode) customers.get(1999)).putArray("externalLinks").addObject()
        .put("system", "jushuitan").put("environment", "production").put("type", "offline")
        .put("id", "EXACT").put("name", "外部名称");
    ObjectNode body = body();
    for (int i = 0; i < 2000; i++) addRow(body, i + 2, "导入门店" + i, i == 0 ? "EXACT" : "external-" + i).put("entity", "共享主体");
    JsonNode preview = service.preview(body, writer);
    assertEquals(1999, preview.path("summary").path("review").asInt());
    assertEquals(1, preview.path("summary").path("matched").asInt());
    for (JsonNode row : preview.path("rows")) {
      assertEquals(20, row.path("matches").size());
      assertEquals(2000, row.path("matchesTotal").asInt());
      assertTrue(row.path("matchesTruncated").asBoolean());
    }
    JsonNode exact = preview.path("rows").get(0);
    assertEquals("store-1999", exact.path("customerId").asText());
    assertTrue(exact.path("matches").findValuesAsText("id").contains("store-1999"));
    verifyNoInteractions(business);
  }

  @Test void externalIdentifierSupportsOneHundredCharactersAndRejectsOneHundredOne() {
    ObjectNode body = body(); addRow(body, 2, "编号边界客户", "a".repeat(100));
    addRow(body, 3, "编号过长客户", "b".repeat(101));
    JsonNode rows = service.preview(body, writer).path("rows");
    assertEquals("NEW", rows.get(0).path("status").asText());
    assertTrue(rows.get(0).path("createable").asBoolean());
    assertEquals("INVALID", rows.get(1).path("status").asText());
    assertFalse(rows.get(1).path("linkable").asBoolean());
    decision(body, 2, "CREATE", null); decision(body, 3, "SKIP", null);
    assertEquals(1, service.commit(body, writer).path("summary").path("created").asInt());
    assertEquals("a".repeat(100), stored.path("customers").get(2).path("externalLinks").get(0).path("id").asText());
  }

  @Test void incompleteNewCustomerCanStillBeExplicitlyLinked() {
    ObjectNode body = body(); ObjectNode row = ((ArrayNode) body.path("rows")).addObject().put("rowNumber", 2).put("name", "外部客户").put("externalId", "ext-1");
    JsonNode preview = service.preview(body, writer).path("rows").get(0);
    assertEquals("INVALID", preview.path("status").asText()); assertTrue(preview.path("linkable").asBoolean());
    assertFalse(preview.path("createable").asBoolean()); decision(body, 2, "LINK", "c1");
    assertEquals(1, service.commit(body, writer).path("summary").path("linked").asInt());
    assertEquals("已有客户", existing().path("name").asText());
  }

  @Test void repeatedImportIsNoOpAndCannotMoveExternalAssociation() {
    existingLink("production", "offline", "ext-1"); ObjectNode before = stored.deepCopy();
    ObjectNode body = body(); addRow(body, 2, "外部改名", "ext-1");
    assertEquals("MATCHED", service.preview(body, writer).path("rows").get(0).path("status").asText());
    decision(body, 2, "LINK", "c2");
    assertThrows(ApiException.class, () -> service.commit(body, writer));
    ((ObjectNode) body.path("decisions").get(0)).put("customerId", "c1");
    JsonNode result = service.commit(body, writer);
    assertEquals(1, result.path("summary").path("skipped").asInt());
    assertEquals(before, stored); verifyNoInteractions(business);
  }

  @Test void environmentAndCustomerTypeKeepExternalIdentitySeparate() {
    existingLink("sandbox", "offline", "ext-1"); existingLink("production", "distributor", "ext-1");
    ObjectNode body = body(); addRow(body, 2, "独立正式客户", "ext-1");
    assertEquals("NEW", service.preview(body, writer).path("rows").get(0).path("status").asText());
  }

  @Test void staleRevisionAndReadOnlyActorsCannotCommit() {
    ObjectNode body = body(); addRow(body, 2, "新增客户", "x1"); decision(body, 2, "CREATE", null); body.put("revision", 6);
    assertEquals(409, assertThrows(ApiException.class, () -> service.commit(body, writer)).status());
    Actor reader = new Actor("reader", "只读", Set.of("business.read"));
    assertEquals(403, assertThrows(ApiException.class, () -> service.preview(body, reader)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.commit(body, reader)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.parse("x.csv", new byte[0], reader)).status());
    verifyNoInteractions(business);
  }

  @Test void missingOrDuplicateDecisionsFailBeforeAnyWrite() {
    ObjectNode body = body(); addRow(body, 2, "新增客户", "x1"); addRow(body, 3, "新增客户二", "x2");
    decision(body, 2, "CREATE", null);
    assertThrows(ApiException.class, () -> service.commit(body, writer));
    decision(body, 2, "SKIP", null);
    assertThrows(ApiException.class, () -> service.commit(body, writer)); verifyNoInteractions(business);
  }

  @Test void failureAfterValidRowIsAtomicAndAllSkipKeepsRevision() {
    ObjectNode before = stored.deepCopy(); ObjectNode body = body(); addRow(body, 2, "新增客户", "x1"); addRow(body, 3, "新增客户二", "x2");
    decision(body, 2, "CREATE", null); decision(body, 3, "LINK", "missing");
    assertThrows(ApiException.class, () -> service.commit(body, writer)); assertEquals(before, stored); verifyNoInteractions(business);
    for (JsonNode decision : body.path("decisions")) ((ObjectNode) decision).put("action", "SKIP");
    JsonNode result = service.commit(body, writer); assertEquals(2, result.path("summary").path("skipped").asInt());
    assertEquals(7, result.path("state").path("revision").asInt()); verifyNoInteractions(business);
  }

  @Test void forgedFundsNonTextIdsAndExcessiveValuesAreRejected() {
    ObjectNode body = body(); ObjectNode row = addRow(body, 2, "新增客户", "x1").put("balance", 100000);
    assertThrows(ApiException.class, () -> service.preview(body, writer)); row.remove("balance");
    row.put("externalId", 123456789);
    assertEquals("INVALID", service.preview(body, writer).path("rows").get(0).path("status").asText());
    row.put("externalId", "1.2E+15").put("name", "x".repeat(201));
    JsonNode invalid = service.preview(body, writer).path("rows").get(0);
    assertFalse(invalid.path("linkable").asBoolean()); assertFalse(invalid.path("createable").asBoolean());
    verifyNoInteractions(business);
  }
}
