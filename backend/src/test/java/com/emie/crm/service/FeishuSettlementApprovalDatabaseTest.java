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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real persistence with synthetic ERP and Feishu responses, restricted to the disposable fixture database. */
@EnabledIfEnvironmentVariable(named = "CRM_ORDER_APPROVAL_DB_TEST", matches = "1")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class FeishuSettlementApprovalDatabaseTest {
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
  private JushuitanDocumentService documents;
  private JushuitanClient erp;
  private FeishuApprovalClient client;
  private FeishuSalesApprovalService approvals;
  private JushuitanSettlementService settlements;

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
    jdbc.update("INSERT INTO user_profiles(name,display_name) VALUES('sales','销售员')");
    business = new BusinessService(db, mock(AssetService.class));
    erp = mock(JushuitanClient.class);
    when(erp.query(eq(JushuitanDocumentService.SALES), anyMap())).thenReturn(mapper.readTree("""
        {"code":0,"data":{"orders":[
          {"shop_id":7,"o_id":11,"so_id":"fixture-so-11","status":"Sent","send_date":"2026-11-01 10:00:00",
           "pay_amount":24,"wms_co_id":4,"items":[{"sku_id":"FIXTURE-SKU","name":"已发货商品","qty":2}]},
          {"shop_id":7,"o_id":12,"so_id":"fixture-so-12","status":"Sent","send_date":"2026-11-01 11:00:00",
           "pay_amount":24,"wms_co_id":4,"items":[{"sku_id":"OTHER-SKU","name":"另一张已发货商品","qty":1}]}
        ]}}
        """));
    documents = new JushuitanDocumentService(db, erp, manager, "sandbox");
    client = mock(FeishuApprovalClient.class);
    when(client.identityReady()).thenReturn(true);
    when(client.enabled(anyString())).thenReturn(true);
    when(client.ready(anyString())).thenReturn(true);
    when(client.appId()).thenReturn("fixture-app");
    when(client.approvalCode(anyString())).thenReturn("fixture-template");
    when(client.definition("fixture-template")).thenReturn(FeishuDomesticApprovalTemplateTest.definition("sales"));
    when(client.create(eq("fixture-template"), eq("ou_sales"), anyString(), any(ArrayNode.class))).thenReturn("fixture-instance");
    var executor = mock(AsyncTaskExecutor.class);
    doAnswer(call -> { workers.add(call.getArgument(0, Runnable.class)); return null; }).when(executor).execute(any(Runnable.class));
    approvals = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor);
    settlements = new JushuitanSettlementService(db, documents, manager, approvals);
  }

  @Test void settlementLinksAndApprovalQueueCommitTogetherBeforeExternalCreation() throws Exception {
    var response = settlements.create(request(), writer);
    String orderId = response.path("approval").path("orderId").asText();
    assertFalse(orderId.isBlank());
    assertEquals("QUEUED", response.path("approval").path("status").asText());
    assertEquals("审批中", currentOrder().path("status").asText());
    assertEquals("jushuitan-settlement", currentOrder().path("source").asText());
    assertTrue(currentOrder().path("items").isEmpty());
    assertEquals(1, count("jst_document_sales"));
    assertEquals(1, count("feishu_order_approvals"));
    var record = record(orderId);
    assertEquals("11", record.path("orderSnapshot").path("settlement").path("sales").get(0).path("id").asText());
    assertTrue(record.path("form").toString().contains("已发货商品"));
    assertEquals(0, count("receipts"));
    assertEquals(0, count("jst_shipments"));
    verify(client, never()).create(any(), any(), any(), any());
    runWorkers();
    assertEquals("PENDING", record(orderId).path("status").asText());
    verify(client).create(eq("fixture-template"), eq("ou_sales"), eq(record.path("uuid").asText()), any(ArrayNode.class));
  }

  @Test void approvalSnapshotDoesNotMixSalesFromAnotherErpEnvironment() throws Exception {
    seedLegacy("审批中");
    String id = currentOrder().path("id").asText();
    jdbc.update("INSERT INTO jst_document_sales(environment,order_id,shop_id,o_id,so_id,sale_metadata) VALUES('production',?,7,999,'other-environment',?)",
        id, "{\"amount\":999,\"items\":[{\"sku\":\"OTHER\",\"name\":\"不得混入的正式销售单\",\"quantity\":1}]}");
    approvals.submit(id, mapper.createObjectNode(), writer);
    var saved = record(id);
    var sales = saved.path("orderSnapshot").path("settlement").path("sales");
    assertEquals(1, sales.size());
    assertEquals("11", sales.get(0).path("id").asText());
    assertFalse(saved.path("form").toString().contains("不得混入"));
  }

  @Test void invalidTemplateRollsBackTheSettlementItsLinksAndAudit() throws Exception {
    when(client.definition("fixture-template")).thenReturn(mapper.readTree("""
        {"form":[{"id":"unsupported","name":"不支持字段","type":"radioV2","required":true}]}
        """));
    var before = db.state(false).deepCopy();
    assertThrows(ApiException.class, () -> settlements.create(request(), writer));
    assertEquals(before, db.state(false));
    for (String table : new String[]{"jst_document_sales", "feishu_order_approvals", "audit", "receipts", "jst_shipments"})
      assertEquals(0, count(table), table);
    assertTrue(workers.isEmpty());
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void stableCreationRequestDoesNotDuplicateSettlementLinksOrExternalApproval() throws Exception {
    var input = request();
    var first = settlements.create(input, writer);
    String id = first.path("approval").path("orderId").asText();
    String uuid = record(id).path("uuid").asText();
    var frozenState = db.state(false).deepCopy();
    var second = settlements.create(input.deepCopy(), writer);
    assertEquals(id, second.path("approval").path("orderId").asText());
    assertEquals(uuid, record(id).path("uuid").asText());
    assertEquals(frozenState, db.state(false));
    assertEquals(1, db.state(false).path("orders").size());
    assertEquals(1, count("jst_document_sales"));
    assertEquals(1, count("feishu_order_approvals"));
    runWorkers();
    assertEquals("PENDING", settlements.create(input, writer).path("approval").path("status").asText());
    runWorkers();
    verify(client, times(1)).create(any(), any(), any(), any());
  }

  @Test void concurrentCopiesOfOneCreationRequestCommitOnlyOneSettlement() throws Exception {
    var input = request();
    var start = new CountDownLatch(1);
    try (var callers = Executors.newFixedThreadPool(2)) {
      var first = callers.submit(() -> { start.await(); return settlements.create(input.deepCopy(), writer); });
      var second = callers.submit(() -> { start.await(); return settlements.create(input.deepCopy(), writer); });
      start.countDown();
      var one = first.get(15, TimeUnit.SECONDS);
      var two = second.get(15, TimeUnit.SECONDS);
      assertEquals(one.path("approval").path("orderId"), two.path("approval").path("orderId"));
    }
    assertEquals(1, db.state(false).path("orders").size());
    assertEquals(1, count("jst_document_sales"));
    assertEquals(1, count("feishu_order_approvals"));
    runWorkers();
    verify(client, times(1)).create(any(), any(), any(), any());
  }

  @ParameterizedTest @ValueSource(strings={"configuration", "identity"})
  void missingFeishuPrerequisiteNeverCreatesAnUnapprovedSettlement(String missing) throws Exception {
    if (missing.equals("configuration")) when(client.ready("销售订单")).thenReturn(false);
    else jdbc.update("DELETE FROM feishu_identities");
    var before = db.state(false).deepCopy();
    assertThrows(ApiException.class, () -> settlements.create(request(), writer));
    assertEquals(before, db.state(false));
    assertEquals(0, count("jst_document_sales"));
    assertEquals(0, count("feishu_order_approvals"));
    assertEquals(0, count("audit"));
    assertTrue(workers.isEmpty());
  }

  @Test void evenAnApproverCannotManuallyApproveALegacySettlement() throws Exception {
    seedLegacy("审批中");
    var before = db.state(false).deepCopy();
    var input = stateBody();
    ((ObjectNode) input.path("data").path("orders").get(0)).put("status", "已批准");
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(input, writer)));
    assertEquals(before, db.state(false));
    assertEquals(0, count("feishu_order_approvals"));
  }

  @ParameterizedTest @ValueSource(strings={"APPROVED", "REJECTED", "CANCELED", "DELETED"})
  void aLegacyPendingSettlementCanSubmitAndReceiveTheRealFeishuOutcome(String outcome) throws Exception {
    seedLegacy("审批中");
    String id = currentOrder().path("id").asText();
    var submitted = approvals.submit(id, mapper.createObjectNode(), writer);
    assertEquals("PENDING", submitted.path("status").asText());
    var record = record(id);
    when(client.instance("fixture-instance")).thenReturn(instance(record, outcome));
    assertEquals(outcome, approvals.sync(id, writer).path("status").asText());
    assertEquals(outcome.equals("APPROVED") ? "已批准" : "草稿", currentOrder().path("status").asText());
    assertEquals(0, count("receipts"));
    assertEquals(0, count("jst_shipments"));
  }

  @Test void rejectedSettlementResubmitsWithANewUuidAndKeepsTheOriginalHistory() throws Exception {
    seedLegacy("审批中");
    String id = currentOrder().path("id").asText();
    approvals.submit(id, mapper.createObjectNode(), writer);
    var original = record(id);
    when(client.instance("fixture-instance")).thenReturn(instance(original, "REJECTED"));
    var rejected = approvals.sync(id, writer);
    assertFalse(rejected.path("retryVersion").asText().isBlank());
    assertThrows(ApiException.class, () -> approvals.submit(id, mapper.createObjectNode(), writer));
    assertEquals("PENDING", approvals.submit(id, mapper.createObjectNode().set("retryVersion", rejected.path("retryVersion")), writer).path("status").asText());
    assertNotEquals(original.path("uuid"), record(id).path("uuid"));
    assertEquals(1, record(id).path("history").size());
    assertEquals("REJECTED", record(id).path("history").get(0).path("status").asText());
    assertEquals(1, count("feishu_order_approvals"));
  }

  @ParameterizedTest @ValueSource(strings={"QUEUED", "PENDING", "APPROVED"})
  void equalAmountRelinkingCannotReplaceTheFrozenApprovedSales(String status) throws Exception {
    var response = settlements.create(request(), writer);
    String id = response.path("approval").path("orderId").asText();
    if (!status.equals("QUEUED")) runWorkers();
    if (status.equals("APPROVED")) {
      when(client.instance("fixture-instance")).thenReturn(instance(record(id), "APPROVED"));
      approvals.sync(id, writer);
    }
    var before = record(id).deepCopy();
    var body = mapper.readTree("""
        {"customerConfirmed":true,"previousSales":[{"shopId":"7","oId":"11"}],"sales":[{"shopId":"7","oId":"12"}]}
        """);
    assertThrows(ApiException.class, () -> documents.replaceSettlementSales(id, body, writer));
    assertEquals("11", jdbc.queryForObject("SELECT o_id FROM jst_document_sales WHERE order_id=?", String.class, id));
    assertEquals(before, record(id));
  }

  private ObjectNode request() throws Exception {
    return (ObjectNode) mapper.readTree("""
        {"approvalRequestId":"af02460c-06c6-4840-a23d-135784aa370f","approval":{},
         "customerConfirmed":true,"customerId":"c1","cycle":"day","periodStart":"2026-11-01","periodEnd":"2026-11-01",
         "total":"24","expectedErpAmount":24,"note":"补录审批隔离测试","adjustmentReason":"",
         "sales":[{"shopId":"7","oId":"11"}]}
        """);
  }

  private void seedLegacy(String status) throws Exception {
    var state = FeishuApprovalFixtures.state();
    var order = FeishuApprovalFixtures.order().put("id", "JS-legacy").put("source", "jushuitan-settlement").put("status", status);
    order.remove("approvalRequestId");
    order.putArray("items");
    order.putObject("settlement").put("cycle", "day").put("periodStart", "2026-11-01").put("periodEnd", "2026-11-01")
        .put("erpAmount", 24).put("adjustmentReason", "");
    state.withArray("orders").add(order);
    var verified = documents.verifySales(request().path("sales"));
    new TransactionTemplate(manager).executeWithoutResult(tx -> {
      db.state(state);
      documents.saveSettlementSales("JS-legacy", verified, writer);
    });
  }

  private ObjectNode stateBody() {
    var state = db.state(false);
    return mapper.createObjectNode().put("revision", state.path("revision").asLong()).set("data", state.deepCopy());
  }
  private ObjectNode currentOrder() { return (ObjectNode) db.state(false).path("orders").get(0); }
  private ObjectNode record(String id) {
    return (ObjectNode) db.parse(jdbc.queryForObject("SELECT payload FROM feishu_order_approvals WHERE order_id=?", String.class, id));
  }
  private ObjectNode instance(JsonNode record, String status) {
    return mapper.createObjectNode().put("approval_code", record.path("approvalCode").asText())
        .put("open_id", record.path("openId").asText()).put("uuid", record.path("uuid").asText())
        .put("instance_code", "fixture-instance").put("status", status);
  }
  private int count(String table) {
    assertTrue(Set.of("jst_document_sales", "feishu_order_approvals", "audit", "receipts", "jst_shipments").contains(table));
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }
  private void runWorkers() { for (Runnable task; (task = workers.poll()) != null;) task.run(); }
}
