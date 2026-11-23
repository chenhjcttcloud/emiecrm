package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

/** Real transactions against an explicitly named disposable fixture, with every external API mocked. */
@EnabledIfEnvironmentVariable(named = "CRM_RECEIPT_APPROVAL_DB_TEST", matches = "1")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class FeishuReceiptApprovalDatabaseTest {
  private static final String DATABASE = "emiecrm_receipt_approval_test";
  private static final String FIXTURE_URL = "jdbc:mysql://emiecrm-receipt-fixture:3306/" + DATABASE
      + "?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=15000";
  private static final String CODE = "receipt-fixture-template";
  private static final String RECEIPT_TRIGGER = "receipt_fixture_fail_receipt";
  private static final String AUDIT_TRIGGER = "receipt_fixture_fail_audit";
  private final ObjectMapper mapper = new ObjectMapper();
  private final Actor finance = new Actor("finance", "财务", Set.of("business.read", "business.write", "payment.write"));
  private final Actor sales = new Actor("sales", "销售", Set.of("business.read", "business.write", "receipt.submit"));
  private final Actor reader = new Actor("reader", "查看人", Set.of("business.read"));
  private JdbcTemplate jdbc;
  private CrmRepository db;
  private DataSourceTransactionManager manager;
  private BusinessService business;
  private FeishuApprovalClient client;
  private FeishuReceiptApprovalService first;
  private FeishuReceiptApprovalService second;

  @BeforeEach void fixture() throws Exception {
    // No application environment variable, local address or production credential is consulted here.
    var source = new DriverManagerDataSource(FIXTURE_URL, "root", "fixture-only");
    jdbc = new JdbcTemplate(source);
    assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
    var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
    schema.execute(source);
    removeFailureTriggers();
    // Retain the real schema: no test substitutes or drops application tables.
    for (String table : jdbc.queryForList("SHOW TABLES", String.class)) {
      assertTrue(table.matches("[a-z][a-z0-9_]*"));
      jdbc.update("DELETE FROM `" + table + "`");
    }
    manager = new DataSourceTransactionManager(source);
    db = new CrmRepository(jdbc, mapper);
    business = new BusinessService(db, mock(AssetService.class));
    ReflectionTestUtils.setField(business, "feishuReceiptApprovalCode", CODE);
    var state = mapper.readTree("""
        {"revision":1,"customers":[{"id":"c1","name":"隔离收款测试客户","balance":0}],
         "products":[{"id":"p1","name":"隔离测试商品","model":"白色","jstSku":"FIXTURE-SKU",
                       "source":"jushuitan","jstEnvironment":"sandbox","enabled":true}],
         "orders":[
          {"id":"sale-1","type":"销售订单","status":"已批准","customerId":"c1","customer":"隔离收款测试客户",
           "owner":"finance","total":100,"paid":0,"items":[{"product":"p1","qty":1,"price":100}]},
          {"id":"deposit-1","type":"预收款订单","status":"已批准","customerId":"c1","customer":"隔离收款测试客户",
           "owner":"finance","total":80,"paid":0,"items":[]}
         ]}
        """);
    jdbc.update("INSERT INTO state(id,payload) VALUES(1,?)", db.encode(state));
    jdbc.update("INSERT INTO payment_methods(id,name,status) VALUES('bank','银行转账','启用')");
    jdbc.update("INSERT INTO feishu_identities(app_id,open_id,user_name) VALUES('fixture-app','ou_finance','finance')");
    jdbc.update("INSERT INTO user_profiles(name,display_name,status) VALUES('finance','测试财务','启用')");
    var salesApproval = mapper.createObjectNode().put("orderId", "sale-1").put("status", "APPROVED")
        .put("uuid", "original-sales-uuid").put("approvalCode", "original-sales-template");
    var shipment = salesApproval.putObject("shipment").put("environment", "sandbox");
    var shipmentInput = shipment.putObject("input").put("shopId", 1).put("warehouseId", 201);
    shipmentInput.putObject("recipient").put("name", "隔离测试勿投递").put("phone", "00000000000")
        .put("province", "广东省").put("city", "深圳市").put("district", "南山区").put("address", "测试勿投递");
    shipmentInput.putArray("items").addObject().put("skuId", "FIXTURE-SKU");
    jdbc.update("INSERT INTO feishu_order_approvals(order_id,status,payload) VALUES('sale-1','APPROVED',?)", db.encode(salesApproval));
    client = mock(FeishuApprovalClient.class);
    when(client.identityReady()).thenReturn(true);
    when(client.receiptEnabled()).thenReturn(true);
    when(client.receiptReady()).thenReturn(true);
    when(client.receiptApprovalCode()).thenReturn(CODE);
    when(client.appId()).thenReturn("fixture-app");
    when(client.definition(CODE)).thenReturn(definition());
    var instanceSequence = new AtomicInteger();
    when(client.create(eq(CODE), eq("ou_finance"), anyString(), any()))
        .thenAnswer(call -> "receipt-instance-" + instanceSequence.incrementAndGet());
    first = new FeishuReceiptApprovalService(db, client, mapper, business, manager);
    second = new FeishuReceiptApprovalService(db, client, mapper, business, manager);

  }

  @AfterEach void cleanupTriggers() {
    if (jdbc != null) removeFailureTriggers();
  }

  private void removeFailureTriggers() {
    jdbc.execute("DROP TRIGGER IF EXISTS " + RECEIPT_TRIGGER);
    jdbc.execute("DROP TRIGGER IF EXISTS " + AUDIT_TRIGGER);
  }

  private ObjectNode definition() throws Exception {
    return (ObjectNode) mapper.readTree("""
        {"status":"ACTIVE","form":[
          {"id":"project","name":"项目","type":"textarea","required":true},
          {"id":"customer","name":"客户","type":"input","required":true},
          {"id":"date","name":"收款发生日期","type":"date","required":true},
          {"id":"category","name":"收款类别","type":"radioV2","required":true,
           "option":[{"text":"销售收入","value":"fixture-sales-income"}]},
          {"id":"amount","name":"收款金额（元）","type":"amount","required":true,"option":{"currencyRange":["CNY"]}},
          {"id":"owner","name":"归属人","type":"contact","required":true},
          {"id":"note","name":"备注","type":"textarea","required":false}
        ],"node_list":[{"need_approver":false}]}
        """);
  }

  private ObjectNode input(String requestId, boolean includeDeposit) {
    var input = mapper.createObjectNode().put("requestId", requestId)
        .put("customerId", "c1").put("amount", includeDeposit ? 130 : 100).put("method", "银行转账")
        .put("receiptDate", "2026-11-18").put("receiptCategory", "销售收入");
    input.set("revision", db.state(false).path("revision").deepCopy());
    var lines = input.putArray("allocations");
    lines.addObject().put("orderId", "sale-1").put("amount", 100);
    if (includeDeposit) lines.addObject().put("orderId", "deposit-1").put("amount", 30);
    return input;
  }

  private ObjectNode record(String id) {
    return (ObjectNode) db.parse(jdbc.queryForObject("SELECT payload FROM feishu_receipt_approvals WHERE id=?", String.class, id));
  }

  private ObjectNode originalSalesApproval() {
    return (ObjectNode) db.parse(jdbc.queryForObject("SELECT payload FROM feishu_order_approvals WHERE order_id='sale-1'", String.class));
  }

  private ObjectNode approvedInstance(JsonNode record) {
    return mapper.createObjectNode().put("instance_code", record.path("instanceCode").asText())
        .put("approval_code", record.path("approvalCode").asText()).put("open_id", record.path("openId").asText())
        .put("uuid", record.path("uuid").asText()).put("status", "APPROVED");
  }

  private <T> List<T> concurrently(Callable<T> left, Callable<T> right) throws Exception {
    var start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var one = workers.submit(() -> {assertTrue(start.await(10, TimeUnit.SECONDS)); return left.call();});
      var two = workers.submit(() -> {assertTrue(start.await(10, TimeUnit.SECONDS)); return right.call();});
      start.countDown();
      return List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private void assertMoneyBookedOnce() {
    var state = db.state(false);
    assertEquals(100, state.at("/orders/0/paid").asInt());
    assertEquals(30, state.at("/orders/1/paid").asInt());
    assertEquals(30, state.at("/customers/0/balance").asInt());
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    assertEquals(130, jdbc.queryForObject("SELECT SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.amount')) AS DECIMAL(18,2))) FROM receipts", java.math.BigDecimal.class).intValueExact());
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM receipts", Integer.class));
    for (var receipt : db.documents("receipts", false, 0)) {
      assertEquals("finance", receipt.path("operator").asText());
      assertEquals("2026-11-18", receipt.path("receiptDate").asText());
      assertFalse(receipt.path("approvalId").asText().isBlank());
      assertFalse(receipt.path("instanceCode").asText().isBlank());
    }
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.action'))='新增收款流水'", Integer.class));
  }

  private void assertNoAutomaticShipment() {
    assertFalse(originalSalesApproval().path("autoShipmentPaused").asBoolean());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jst_shipments", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jst_document_sales", Integer.class));

  }



  @Test void twoIndependentServicesSynchronizeOneApprovalAndBookItsAllocationOnlyOnce() throws Exception {
    first.create(input("concurrent-sync", true), finance);
    var pending = record("concurrent-sync");
    var upstreamReaders = new CountDownLatch(2);
    when(client.instance(pending.path("instanceCode").asText())).thenAnswer(call -> {
      upstreamReaders.countDown();
      assertTrue(upstreamReaders.await(10, TimeUnit.SECONDS));
      return approvedInstance(pending);
    });
    var results = concurrently(() -> first.sync("concurrent-sync", reader), () -> second.sync("concurrent-sync", reader));
    for (var result : results) {
      assertEquals("APPROVED", result.path("approval").path("status").asText());
      assertTrue(result.path("approval").path("booked").asBoolean());
    }
    assertTrue(record("concurrent-sync").path("booked").asBoolean());
    assertMoneyBookedOnce();
    assertNoAutomaticShipment();
    first.sync("concurrent-sync", reader); second.sync("concurrent-sync", reader);
    assertMoneyBookedOnce();
    verify(client, times(1)).create(eq(CODE), eq("ou_finance"), anyString(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"receipt", "audit"})
  void failedReceiptOrAuditInsertRollsBackTheWholePostingAndRetryBooksOnlyOnce(String failure) {
    first.create(input("rollback-" + failure, true), finance);
    var pending = record("rollback-" + failure);
    var original = db.state(false).deepCopy();
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    if ("receipt".equals(failure)) {
      jdbc.execute("CREATE TRIGGER " + RECEIPT_TRIGGER + " BEFORE INSERT ON receipts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture receipt write failure'");
    } else {
      jdbc.execute("CREATE TRIGGER " + AUDIT_TRIGGER + " BEFORE INSERT ON audit FOR EACH ROW BEGIN "
          + "IF JSON_UNQUOTE(JSON_EXTRACT(NEW.payload,'$.action'))='新增收款流水' THEN "
          + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture receipt audit write failure'; END IF; END");
    }
    var response = first.sync(pending.path("id").asText(), reader).path("approval");
    assertEquals("APPROVED", response.path("status").asText());
    assertFalse(response.path("booked").asBoolean());
    assertFalse(response.path("bookingError").asText().isBlank());
    assertEquals("APPROVED", record(pending.path("id").asText()).path("status").asText());
    assertFalse(record(pending.path("id").asText()).path("booked").asBoolean());
    assertFalse(record(pending.path("id").asText()).path("bookingError").asText().isBlank());
    assertEquals(original.path("orders"), db.state(false).path("orders"));
    assertEquals(original.path("customers"), db.state(false).path("customers"));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.action'))='新增收款流水'", Integer.class));
    assertFalse(originalSalesApproval().path("autoShipmentPaused").asBoolean());
    removeFailureTriggers();
    var retried = second.sync(pending.path("id").asText(), reader).path("approval");
    assertTrue(retried.path("booked").asBoolean()); assertFalse(retried.has("bookingError"));
    first.sync(pending.path("id").asText(), reader); second.sync(pending.path("id").asText(), reader);
    assertMoneyBookedOnce();
    assertNoAutomaticShipment();
    verify(client, times(1)).instance(pending.path("instanceCode").asText());
    verify(client, times(1)).create(eq(CODE), eq("ou_finance"), anyString(), any());
  }

  @Test void differentRequestIdsCannotReserveTheSameOrderAndCreateTwoRemoteInstances() throws Exception {
    var left = input("reservation-left", false);
    var right = input("reservation-right", false);
    var definitionReaders = new CountDownLatch(2);
    var template = definition();
    when(client.definition(CODE)).thenAnswer(call -> {
      definitionReaders.countDown();
      assertTrue(definitionReaders.await(10, TimeUnit.SECONDS));
      return template;
    });
    var results = concurrently(() -> createOrConflict(first, left), () -> createOrConflict(second, right));
    assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_receipt_approvals", Integer.class));
    assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM feishu_receipt_approvals", String.class));
    assertEquals(0, db.state(false).at("/orders/0/paid").asInt());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    verify(client, times(1)).create(eq(CODE), eq("ou_finance"), anyString(), any());
    assertNoAutomaticShipment();
  }

  private boolean createOrConflict(FeishuReceiptApprovalService service, JsonNode input) {
    try {
      assertEquals("PENDING", service.create(input, finance).path("approval").path("status").asText());
      return true;
    } catch (ApiException conflict) {
      assertEquals(409, conflict.status());
      return false;
    }
  }
  private void saveFixtureState(JsonNode state) {
    assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
    jdbc.update("UPDATE state SET payload=? WHERE id=1", db.encode(state));
  }

  private ObjectNode partialInput(String id, String order, int amount, String method) {
    var request = input(id, false).put("amount", amount).put("method", method);
    ((ObjectNode) request.path("allocations").get(0)).put("orderId", order).put("amount", amount);
    return request;
  }

  @Test void configuredApprovalsRejectDirectPaidChangesAndForgedRestoreMoney() {
    var before = db.state(false).deepCopy(); var modified = before.deepCopy();
    ((ObjectNode) modified.at("/orders/0")).put("paid", 25).put("method", "银行转账");
    var body = mapper.createObjectNode().put("revision", before.path("revision").asLong()); body.set("data", modified);
    var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    assertTrue(assertThrows(ApiException.class, () -> transaction.execute(status -> business.save(body, finance)))
        .getMessage().contains("收款须通过飞书审批"));
    var backup = mapper.valueToTree(business.backup());
    ((ObjectNode) backup.at("/state/orders/0")).put("paid", 25);
    ((com.fasterxml.jackson.databind.node.ArrayNode) backup.path("receipts")).addObject()
        .put("id", "forged").put("order", "sale-1").put("amount", 25).put("customerId", "c1")
        .put("customer", "隔离收款测试客户").put("type", "销售订单").put("method", "银行转账");
    assertTrue(assertThrows(ApiException.class, () -> transaction.execute(status -> { business.restore(backup, finance); return null; }))
        .getMessage().contains("备份不能新增"));
    assertEquals(before, db.state(false)); assertEquals(0, db.documents("receipts", false, 0).size());
  }

  @Test void approvalAllowsNotesAttachmentsAndCatalogPresentationChangesWithoutChangingSubmittedSnapshot() {
    first.create(input("editable-notes", false), finance);
    var pending = record("editable-notes");
    var current = db.state(false).deepCopy();
    ((ObjectNode) current.at("/orders/0")).put("note", "审批期间补充备注").put("updated", "2026-11-23 14:00:00")
        .putArray("attachments").addObject().put("name", "补充.png").put("url", "/api/assets/test");
    var body = mapper.createObjectNode().put("revision", current.path("revision").asLong()); body.set("data", current);
    new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> business.save(body, finance));
    current = db.state(false).deepCopy(); ((ObjectNode) current.at("/products/0")).put("name", "商品后来的名称");
    saveFixtureState(current);
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    var result = first.sync("editable-notes", reader).path("approval");
    assertTrue(result.path("booked").asBoolean(), result.path("bookingError").asText());
    var receipt = db.documents("receipts", false, 0).getFirst();
    assertEquals("隔离测试商品", receipt.at("/items/0/name").asText());
    assertEquals("snapshot", receipt.path("itemsSource").asText());
    assertEquals("审批期间补充备注", db.state(false).at("/orders/0/note").asText());
  }

  private void creditFixture() {
    var state = db.state(false).deepCopy(); ((ObjectNode) state.at("/customers/0")).put("balance", 70);
    ((ObjectNode) state.at("/orders/1")).put("paid", 70);
    var secondSale = ((ObjectNode) state.at("/orders/0")).deepCopy().put("id", "sale-2");
    ((com.fasterxml.jackson.databind.node.ArrayNode) state.path("orders")).add(secondSale);
    saveFixtureState(state);
    db.receipt(mapper.createObjectNode().put("id", "existing-credit").put("order", "deposit-1")
        .put("customerId", "c1").put("customer", "隔离收款测试客户").put("amount", 70)
        .put("method", "银行转账").put("type", "预收款订单"));
  }

  @Test void creditWriteOffReservesAvailableBalanceAndDebitsOnlyAfterApproval() {
    creditFixture();
    first.create(partialInput("credit-one", "sale-1", 40, "预收款核销"), finance);
    assertEquals(70, db.state(false).at("/customers/0/balance").asInt());
    assertTrue(assertThrows(ApiException.class,
        () -> second.create(partialInput("credit-two", "sale-2", 40, "预收款核销"), finance))
        .getMessage().contains("余额不足"));
    var pending = record("credit-one");
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    assertTrue(first.sync("credit-one", reader).path("approval").path("booked").asBoolean());
    first.sync("credit-one", reader);
    assertEquals(30, db.state(false).at("/customers/0/balance").asInt());
    assertEquals(40, db.state(false).at("/orders/0/paid").asInt());
    assertEquals(2, db.documents("receipts", false, 0).size());
    assertNoAutomaticShipment();
  }

  @Test void writeOffRejectsDepositsAndInsufficientBalanceBeforeCreatingRemoteApproval() {
    var state = db.state(false).deepCopy(); ((ObjectNode) state.at("/customers/0")).put("balance", 100);
    saveFixtureState(state);
    assertThrows(ApiException.class, () -> first.create(partialInput("invalid-deposit-writeoff", "deposit-1", 10, "预收款核销"), finance));
    ((ObjectNode) state.at("/customers/0")).put("balance", 0); saveFixtureState(state);
    assertThrows(ApiException.class, () -> first.create(partialInput("insufficient-credit", "sale-1", 10, "预收款核销"), finance));
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void settlementReceiptRetainsDomesticReceivedDateAndCannotUseCreditWriteOff() {
    var state = db.state(false).deepCopy(); var order = (ObjectNode) state.at("/orders/0");
    order.put("source", "jushuitan-settlement").putArray("items");
    order.putObject("settlement").put("cycle", "month").put("periodStart", "2026-11-01").put("periodEnd", "2026-11-20");
    saveFixtureState(state);
    var bad = input("bad-settlement-date", false).put("receiptDate", "2026-10-31");
    assertThrows(ApiException.class, () -> first.create(bad, finance));
    ((ObjectNode) state.at("/customers/0")).put("balance", 100); saveFixtureState(state);
    assertThrows(ApiException.class, () -> first.create(partialInput("bad-settlement-writeoff", "sale-1", 10, "预收款核销"), finance));
    first.create(input("settlement-receipt", false), finance);
    var pending = record("settlement-receipt");
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    var result = first.sync("settlement-receipt", reader).path("approval");
    assertTrue(result.path("booked").asBoolean(), result.path("bookingError").asText());
    assertEquals("2026-11-18", db.documents("receipts", false, 0).getFirst().path("receivedOn").asText());
  }

  @Test void aDuplicateRemoteInstanceCannotOverwriteAnotherReceiptBatch() {
    first.create(partialInput("first-instance-owner", "sale-1", 10, "银行转账"), finance);
    var saved = record("first-instance-owner").deepCopy();
    when(client.create(eq(CODE), eq("ou_finance"), anyString(), any())).thenReturn(saved.path("instanceCode").asText());
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
        () -> second.create(partialInput("instance-collision", "deposit-1", 10, "银行转账"), finance));
    assertEquals(saved, record("first-instance-owner"));
    assertFalse(record("instance-collision").hasNonNull("instanceCode"));
    assertEquals(0, db.documents("receipts", false, 0).size());
  }

  @Test void executionChangesCannotBePostedFromAnOlderReceiptApproval() {
    first.create(input("execution-changes", false), finance);
    var pending = record("execution-changes");
    var payload = mapper.createObjectNode(); payload.set("snapshot", db.state(false).at("/orders/0").deepCopy());
    payload.set("effectiveOrder", db.state(false).at("/orders/0").deepCopy());
    ((ObjectNode) payload.path("effectiveOrder")).put("total", 200);
    jdbc.update("INSERT INTO jst_shipments(order_id,payload) VALUES('sale-1',?)", db.encode(payload));
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    var result = first.sync("execution-changes", reader).path("approval");
    assertEquals("APPROVED", result.path("status").asText()); assertFalse(result.path("booked").asBoolean());
    assertTrue(result.path("bookingError").asText().contains("执行信息已变化"));
    assertEquals(0, db.documents("receipts", false, 0).size());
  }

  @Test void existingExecutionMoneyDifferencesAreRejectedBeforeRemoteSubmissionOrReservation() {
    var payload = mapper.createObjectNode(); payload.set("snapshot", db.state(false).at("/orders/0").deepCopy());
    payload.set("effectiveOrder", db.state(false).at("/orders/0").deepCopy());
    ((ObjectNode) payload.path("effectiveOrder")).put("total", 200);
    jdbc.update("INSERT INTO jst_shipments(order_id,payload) VALUES('sale-1',?)", db.encode(payload));
    assertTrue(assertThrows(ApiException.class, () -> first.create(input("preexisting-execution-difference", false), finance))
        .getMessage().contains("原变更差额"));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_receipt_approvals", Integer.class));
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void latestBackupAfterCustomerRenameCanRestoreButCannotAlterApprovedMoney() {
    jdbc.update("DELETE FROM feishu_order_approvals");
    first.create(input("rename-then-backup", false), finance);
    var pending = record("rename-then-backup");
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    assertTrue(first.sync("rename-then-backup", reader).path("approval").path("booked").asBoolean());
    var current = db.state(false).deepCopy(); ((ObjectNode) current.at("/customers/0")).put("name", "新客户名称");
    var body = mapper.createObjectNode().put("revision", current.path("revision").asLong()); body.set("data", current);
    var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    transaction.execute(status -> business.save(body, finance));
    var backup = mapper.valueToTree(business.backup());
    assertEquals("新客户名称", backup.at("/receipts/0/customer").asText());
    transaction.execute(status -> {business.restore(backup, finance); return null;});
    assertEquals(100, db.state(false).at("/orders/0/paid").asInt());
    ((ObjectNode) backup.at("/receipts/0")).put("amount", 99);
    assertThrows(ApiException.class, () -> transaction.execute(status -> {business.restore(backup, finance); return null;}));
  }

}
