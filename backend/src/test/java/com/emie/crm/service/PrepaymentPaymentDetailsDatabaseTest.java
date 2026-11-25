package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only against the explicitly enabled disposable approval fixture. */
@EnabledIfEnvironmentVariable(named="CRM_ORDER_APPROVAL_DB_TEST", matches="1")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class PrepaymentPaymentDetailsDatabaseTest {
  private static final String DATABASE = "emiecrm_order_approval_test";
  private static final String URL = "jdbc:mysql://emiecrm-order-approval-fixture:3306/" + DATABASE
      + "?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=15000";
  private final ObjectMapper mapper = new ObjectMapper();
  private final Actor writer = new Actor("sales", "销售", Set.of("business.read", "business.write", "order.approve", "data.backup"));
  private final ConcurrentLinkedQueue<Runnable> workers = new ConcurrentLinkedQueue<>();
  private JdbcTemplate jdbc;
  private CrmRepository db;
  private DataSourceTransactionManager manager;
  private BusinessService business;
  private FeishuApprovalClient client;
  private FeishuSalesApprovalService service;
  private JsonNode definition;

  @BeforeEach void fixture() throws Exception {
    var source = new DriverManagerDataSource(URL, "root", "fixture-only");
    jdbc = new JdbcTemplate(source);
    assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    for (String table : jdbc.queryForList("SHOW TABLES", String.class)) {
      assertTrue(table.matches("[a-z][a-z0-9_]*"));
      jdbc.update("DELETE FROM `" + table + "`");
    }
    manager = new DataSourceTransactionManager(source);
    db = new CrmRepository(jdbc, mapper);
    jdbc.update("INSERT INTO state(id,payload) VALUES(1,?)", db.encode(FeishuApprovalFixtures.state()));
    jdbc.update("INSERT INTO feishu_identities(app_id,open_id,user_name) VALUES('fixture-app','ou_sales','sales')");
    jdbc.update("INSERT INTO user_profiles(name,display_name,status) VALUES('sales','销售员','启用')");
    jdbc.update("INSERT INTO payment_methods(id,name,status) VALUES('bank','测试银行','启用'),('wallet','测试钱包','启用')");
    var assets = mock(AssetService.class);
    when(assets.backup(any())).thenReturn(java.util.Map.of());
    business = new BusinessService(db, assets);
    client = mock(FeishuApprovalClient.class);
    when(client.identityReady()).thenReturn(true);
    when(client.enabled(anyString())).thenReturn(true);
    when(client.ready(anyString())).thenReturn(true);
    when(client.appId()).thenReturn("fixture-app");
    when(client.approvalCode(anyString())).thenReturn("fixture-template");
    definition = FeishuDomesticApprovalTemplateTest.definition("prepayment");
    when(client.definition("fixture-template")).thenReturn(definition);
    when(client.create(eq("fixture-template"), eq("ou_sales"), anyString(), any(ArrayNode.class))).thenReturn("fixture-instance");
    var executor = mock(AsyncTaskExecutor.class);
    doAnswer(call -> {workers.add(call.getArgument(0, Runnable.class)); return null;}).when(executor).execute(any(Runnable.class));
    service = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor);
  }

  @Test void canonicalRowsAreFrozenBeforeCreateAndApprovalNeverCreatesActualCash() throws Exception {
    service.createAndSubmit("o1", request(), writer);
    var persisted = currentOrder().path("paymentDetails");
    assertEquals(2, persisted.size());
    assertEquals(1, persisted.get(0).path("lineNo").asInt());
    assertEquals(2, persisted.get(1).path("lineNo").asInt());
    assertEquals(persisted, record().path("orderSnapshot").path("paymentDetails"));
    assertEquals(300, currentOrder().path("total").asInt());
    assertEquals("组合收款", currentOrder().path("method").asText());
    assertEquals("2026-11-25", currentOrder().path("approvalDraft").path("receiptDate").asText());
    assertEquals("QUEUED", record().path("status").asText());
    verify(client, never()).create(any(), any(), any(), any());
    String project = nativeField(record(), "项目").path("value").asText();
    assertTrue(project.contains("测试银行") && project.contains("100.00") && project.contains("2026-11-24"));
    assertTrue(project.contains("测试钱包") && project.contains("200.00") && project.contains("2026-11-25"));
    assertEquals(300, nativeField(record(), "收款金额（元）").path("value").asInt());
    assertEquals("2026-11-25T00:00:00+08:00", nativeField(record(), "收款发生日期").path("value").asText());
    runWorkers();
    when(client.instance("fixture-instance")).thenReturn(instance("APPROVED"));
    service.sync("o1", writer);
    assertEquals("已批准", currentOrder().path("status").asText());
    assertEquals(persisted, currentOrder().path("paymentDetails"));
    assertNoCash();
  }

  @Test void unknownRetryUsesTheExactUuidAndFormAfterConfiguredMethodsChange() throws Exception {
    var constructor = FeishuApprovalClient.Uncertain.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    when(client.create(any(), any(), any(), any(ArrayNode.class))).thenThrow(constructor.newInstance()).thenReturn("fixture-instance");
    service.createAndSubmit("o1", request(), writer);
    String uuid = record().path("uuid").asText();
    var originalForm = record().path("form").deepCopy();
    var originalRows = record().path("orderSnapshot").path("paymentDetails").deepCopy();
    runWorkers();
    assertEquals("UNKNOWN", record().path("status").asText());
    jdbc.update("UPDATE payment_methods SET status='停用'");
    service.submit("o1", mapper.createObjectNode().put("retryOriginal", true).put("originalVersion", uuid), writer);
    assertEquals("PENDING", record().path("status").asText());
    assertEquals(originalRows, record().path("orderSnapshot").path("paymentDetails"));
    var forms = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
    verify(client, times(2)).create(eq("fixture-template"), eq("ou_sales"), eq(uuid), forms.capture());
    assertEquals(originalForm, forms.getAllValues().get(0));
    assertEquals(originalForm, forms.getAllValues().get(1));
    assertNoCash();
  }

  @Test void laterActualReceiptKeepsApprovedProposalRowsAndUsesItsOwnRealMethod() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    when(client.instance("fixture-instance")).thenReturn(instance("APPROVED"));
    service.sync("o1", writer);
    var originalRows = currentOrder().path("paymentDetails").deepCopy();
    var payment = stateBody();
    order(payment.path("data")).put("paid", 100).put("method", "测试银行");
    var finance = new Actor("finance", "财务", Set.of("business.read", "business.write", "payment.write"));
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(payment, finance));
    assertEquals(originalRows, currentOrder().path("paymentDetails"));
    assertEquals(originalRows, record().path("orderSnapshot").path("paymentDetails"));
    assertEquals(300, currentOrder().path("total").asInt());
    assertEquals(100, currentOrder().path("paid").asInt());
    assertEquals(100, db.state(false).path("customers").get(0).path("balance").asInt());
    var receipt = db.parse(jdbc.queryForObject("SELECT payload FROM receipts", String.class));
    assertEquals(100, receipt.path("amount").asInt());
    assertEquals("测试银行", receipt.path("method").asText());
  }

  @Test void equalTotalRowChangesCannotBypassPendingSaveOrBackupRestoreLocks() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    var original = currentOrder().deepCopy();
    var update = stateBody();
    alterRows(order(update.path("data")));
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(update, writer)));
    assertEquals(original, currentOrder());
    var backup = (ObjectNode) mapper.valueToTree(business.backup());
    alterRows(order(backup.path("state")));
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.restore(backup, writer)));
    assertEquals(original, currentOrder());
    assertNoCash();
  }

  @Test void disabledDetailMethodAndInconsistentSummariesRollBackTheWholeSubmission() throws Exception {
    var initial = db.state(false).deepCopy();
    var wrongTotal = request(); order(wrongTotal.path("data")).put("total", 301);
    assertThrows(ApiException.class, () -> service.createAndSubmit("o1", wrongTotal, writer));
    assertEquals(initial, db.state(false));
    jdbc.update("UPDATE payment_methods SET status='停用' WHERE id='wallet'");
    assertThrows(ApiException.class, () -> service.createAndSubmit("o1", request(), writer));
    assertEquals(initial, db.state(false));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    verify(client, never()).create(any(), any(), any(), any());
    assertNoCash();
  }

  @Test void differentApprovalRowsAreRejectedEvenWhenTheTotalMatches() throws Exception {
    var initial = db.state(false).deepCopy();
    var input = request();
    ((ObjectNode) input.path("approval").path("paymentDetails").get(0)).put("receiptDate", "2026-11-23");
    assertThrows(ApiException.class, () -> service.createAndSubmit("o1", input, writer));
    assertEquals(initial, db.state(false));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    verify(client, never()).create(any(), any(), any(), any());
    assertNoCash();
  }

  @Test void repairingALegacyCustomerLinkCannotAlsoRemoveApprovedProposalRows() throws Exception {
    var input = request();
    var seed = (ObjectNode) input.path("data").deepCopy();
    var legacy = order(seed);
    legacy.put("status", "已批准").put("customer", "未关联旧客户").remove("customerId");
    legacy.set("paymentDetails", input.path("approval").path("paymentDetails").deepCopy());
    db.state(seed);
    var edited = stateBody();
    var replacement = order(edited.path("data"));
    replacement.put("customerId", "c1").put("customer", "隔离测试客户").remove("paymentDetails");
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(edited, writer)));
    assertEquals(legacy, currentOrder());
    replacement.set("paymentDetails", legacy.path("paymentDetails").deepCopy());
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(edited, writer));
    assertEquals("c1", currentOrder().path("customerId").asText());
    assertEquals(legacy.path("paymentDetails"), currentOrder().path("paymentDetails"));
    assertNoCash();
  }

  private ObjectNode request() throws Exception {
    var result = stateBody();
    var entry = FeishuApprovalFixtures.order().put("type", "预收款订单").put("total", 300).put("method", "组合收款");
    entry.putArray("items");
    entry.putObject("approvalDraft").put("receiptDate", "2026-11-25");
    var rows = entry.putArray("paymentDetails");
    rows.addObject().put("lineNo", 80).put("method", "测试银行").put("amount", "100.00").put("receiptDate", "2026-11-24");
    rows.addObject().put("lineNo", 90).put("method", "测试钱包").put("amount", "200").put("receiptDate", "2026-11-25");
    result.withObject("data").withArray("orders").add(entry);
    var approvalRows = result.putObject("approval").put("receiptDate", "2026-11-25").putArray("paymentDetails");
    approvalRows.addObject().put("lineNo", 1).put("method", "测试银行").put("amount", 100).put("receiptDate", "2026-11-24");
    approvalRows.addObject().put("lineNo", 2).put("method", "测试钱包").put("amount", 200).put("receiptDate", "2026-11-25");
    return result;
  }

  private void alterRows(ObjectNode entry) {
    ((ObjectNode) entry.path("paymentDetails").get(0)).put("amount", 110);
    ((ObjectNode) entry.path("paymentDetails").get(1)).put("amount", 190);
  }
  private ObjectNode stateBody() {
    var state = db.state(false);
    var body = mapper.createObjectNode().put("revision", state.path("revision").asLong());
    body.set("data", state.deepCopy()); return body;
  }
  private ObjectNode order(JsonNode state) { return (ObjectNode) state.path("orders").get(0); }
  private ObjectNode currentOrder() { return order(db.state(false)); }
  private ObjectNode record() {
    return (ObjectNode) db.parse(jdbc.queryForObject("SELECT payload FROM feishu_order_approvals WHERE order_id='o1'", String.class));
  }
  private JsonNode nativeField(JsonNode record, String name) {
    String id = "";
    for (var widget : definition.path("form")) if (name.equals(widget.path("name").asText())) id = widget.path("id").asText();
    assertFalse(id.isEmpty());
    for (var field : record.path("form")) if (id.equals(field.path("id").asText())) return field;
    fail("Missing native field: " + name); return null;
  }
  private ObjectNode instance(String status) {
    var record = record();
    return mapper.createObjectNode().put("approval_code", record.path("approvalCode").asText())
        .put("open_id", record.path("openId").asText()).put("uuid", record.path("uuid").asText())
        .put("instance_code", "fixture-instance").put("status", status);
  }
  private void assertNoCash() {
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jst_shipments", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jst_credit_pushes", Integer.class));
    assertTrue(db.state(false).path("customers").get(0).path("balance").decimalValue().signum() == 0);
    if (!db.state(false).path("orders").isEmpty()) assertEquals(0, currentOrder().path("paid").asInt());
  }
  private void runWorkers() { for (Runnable task; (task = workers.poll()) != null;) task.run(); }
}
