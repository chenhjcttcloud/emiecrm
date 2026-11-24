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
    assertTrue(currentOrder().path("orderNumber").asText().matches("[0-9]{4}-[0-9]{2}-00000001"));
    assertEquals(orderId, currentOrder().path("id").asText());
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

  @Test void originalLogisticsPersistInSettlementLinksApprovalSnapshotAndBackupWithoutCreatingShipments() throws Exception {
    when(erp.query(eq(JushuitanDocumentService.SALES),anyMap())).thenReturn(mapper.readTree("""
      {"code":0,"data":{"orders":[{"shop_id":7,"o_id":11,"so_id":"fixture-so-11","status":"Sent",
       "send_date":"2026-11-01 10:00:00","pay_amount":24,"wms_co_id":4,"logistics_company":"原始已发货物流",
       "l_id":"000012345ABC","items":[{"sku_id":"FIXTURE-SKU","name":"已发货商品","qty":2}]}]}}
      """));
    var input=request().put("logisticsCompany","不得使用的前端值").put("trackingNumber","FAKE");
    var response=settlements.create(input,writer);String id=response.path("approval").path("orderId").asText();
    var saved=record(id);var sale=saved.path("orderSnapshot").path("settlement").path("sales").get(0);
    assertEquals("原始已发货物流",sale.path("logisticsCompany").asText());assertEquals("000012345ABC",sale.path("trackingNumber").asText());
    assertTrue(saved.path("form").toString().contains("原物流：原始已发货物流；原运单：000012345ABC"));
    assertFalse(saved.toString().contains("不得使用的前端值"));assertFalse(saved.toString().contains("FAKE"));
    var metadata=mapper.readTree(jdbc.queryForObject("SELECT sale_metadata FROM jst_document_sales WHERE order_id=?",String.class,id));
    assertEquals("原始已发货物流",metadata.path("logisticsCompany").asText());assertEquals("000012345ABC",metadata.path("trackingNumber").asText());
    var backup=mapper.valueToTree(business.backup());
    new TransactionTemplate(manager).executeWithoutResult(tx->business.restore(backup,writer));
    assertEquals(metadata,mapper.readTree(jdbc.queryForObject("SELECT sale_metadata FROM jst_document_sales WHERE order_id=?",String.class,id)));
    assertEquals(saved,record(id));assertEquals(0,count("jst_shipments"));assertEquals(0,count("receipts"));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM jst_credit_pushes",Integer.class));
    verify(erp,never()).upload(any());
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
    assertEquals(first.path("state").path("orders").get(0).path("orderNumber"), second.path("state").path("orders").get(0).path("orderNumber"));
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

  @Test void backfillIsIdempotentAndDoesNotRewriteApprovalOrErpReferences() throws Exception {
    seedLegacy("审批中");
    String id=currentOrder().path("id").asText();
    approvals.submit(id,mapper.createObjectNode(),writer);
    var approval=record(id).deepCopy();
    var original=currentOrder().deepCopy();
    String links=jdbc.queryForObject("SELECT sale_metadata FROM jst_document_sales WHERE order_id=?",String.class,id);
    long revision=db.state(false).path("revision").asLong();
    var migration=new SettlementOrderNumberMigration(db,manager);
    migration.run(null);
    String number=currentOrder().path("orderNumber").asText();
    assertTrue(number.matches("[0-9]{4}-[0-9]{2}-00000001"));
    var migrated=currentOrder().deepCopy();migrated.remove("orderNumber");
    assertEquals(original,migrated);
    assertEquals(approval,record(id));
    assertEquals(links,jdbc.queryForObject("SELECT sale_metadata FROM jst_document_sales WHERE order_id=?",String.class,id));
    assertEquals(revision+1,db.state(false).path("revision").asLong());
    var saved=db.state(false).deepCopy();int audits=count("audit");
    migration.run(null);
    assertEquals(saved,db.state(false));assertEquals(audits,count("audit"));
    var next=request();next.withArray("sales").removeAll().addObject().put("shopId","7").put("oId","12");
    var response=settlements.create(next,writer);
    assertTrue(response.path("state").path("orders").get(0).path("orderNumber").asText().endsWith("00000002"));
    assertEquals(id,response.path("state").path("orders").get(1).path("id").asText());
  }

  @Test void numberedSettlementCannotBeRenumberedByAnOrdinaryStateSave() throws Exception {
    settlements.create(request(),writer);
    var before=db.state(false).deepCopy();
    for(boolean remove:new boolean[]{false,true}) {
      var body=stateBody();var order=(ObjectNode)body.path("data").path("orders").get(0);
      if(remove) order.remove("orderNumber");else order.put("orderNumber","2026-11-99999999");
      assertThrows(ApiException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->business.save(body,writer)));
      assertEquals(before,db.state(false));
    }
    var body=stateBody();((ObjectNode)body.path("data").path("orders").get(0)).put("note","补充说明");
    new TransactionTemplate(manager).executeWithoutResult(tx->business.save(body,writer));
    assertEquals(before.path("orders").get(0).path("orderNumber"),currentOrder().path("orderNumber"));
  }

  @Test void restoringABackupWithoutTheNewFieldRetainsThePersistedBusinessNumber() throws Exception {
    seedLegacy("审批中");
    new SettlementOrderNumberMigration(db,manager).run(null);
    var assets=mock(AssetService.class);when(assets.backup(any())).thenReturn(java.util.Map.of());
    var withAssets=new BusinessService(db,assets);
    ObjectNode backup=mapper.valueToTree(withAssets.backup());
    String id=currentOrder().path("id").asText(),number=currentOrder().path("orderNumber").asText();
    ((ObjectNode)backup.path("state").path("orders").get(0)).remove("orderNumber");
    new TransactionTemplate(manager).executeWithoutResult(tx->withAssets.restore(backup,writer));
    assertEquals(id,currentOrder().path("id").asText());assertEquals(number,currentOrder().path("orderNumber").asText());
    assertEquals(1,count("jst_document_sales"));
  }

  @Test void concurrentDistinctRequestsHaveUniqueConsecutiveBusinessNumbers() throws Exception {
    var first=request();var second=request().put("approvalRequestId","c663f2ed-a039-46f3-9bd6-556a5b5a3bb8");
    second.withArray("sales").removeAll().addObject().put("shopId","7").put("oId","12");
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start=new java.util.concurrent.CountDownLatch(1);
      var a=pool.submit(()->{start.await();return settlements.create(first,writer);});
      var b=pool.submit(()->{start.await();return settlements.create(second,writer);});
      start.countDown();a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);
    }
    var numbers=new java.util.HashSet<String>();
    for(var order:db.state(false).path("orders")) numbers.add(order.path("orderNumber").asText());
    String prefix=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"))+"-";
    assertEquals(Set.of(prefix+"00000001",prefix+"00000002"),numbers);
    assertEquals(2,count("feishu_order_approvals"));assertEquals(2,count("jst_document_sales"));
  }

  @Test void ordinaryOrdersAndSupplementsUseOneSharedNumberSequence() throws Exception {
    String prefix=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"))+"-";
    new TransactionTemplate(manager).executeWithoutResult(tx->business.save(ordinaryBody(prefix+"00000006"),writer));
    var first=settlements.create(request(),writer);
    assertEquals(prefix+"00000007",first.path("state").path("orders").get(0).path("orderNumber").asText());
    new TransactionTemplate(manager).executeWithoutResult(tx->business.save(ordinaryBody(prefix+"00000008"),writer));
    var second=request().put("approvalRequestId","42144215-6dfe-4987-8e9b-fc90da4fb346");
    second.withArray("sales").removeAll().addObject().put("shopId","7").put("oId","12");
    settlements.create(second,writer);
    var numbers=new java.util.HashSet<String>();
    for(var order:db.state(false).path("orders")) numbers.add(OrderNumbers.display(order));
    assertEquals(Set.of(prefix+"00000006",prefix+"00000007",prefix+"00000008",prefix+"00000009"),numbers);
  }

  @Test void anOrdinaryOrderCannotUseAnAlreadyAssignedSupplementNumber() throws Exception {
    settlements.create(request(),writer);
    String number=currentOrder().path("orderNumber").asText();var before=db.state(false).deepCopy();
    assertEquals(409,assertThrows(ApiException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->business.save(ordinaryBody(number),writer))).status());
    assertEquals(before,db.state(false));
  }

  @Test void concurrentOrdinaryAndSupplementCreationCannotCommitTheSameNumber() throws Exception {
    String prefix=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"))+"-";
    new TransactionTemplate(manager).executeWithoutResult(tx->business.save(ordinaryBody(prefix+"00000006"),writer));
    var ordinary=ordinaryBody(prefix+"00000007");
    int ordinaryStatus;
    try(var pool=Executors.newFixedThreadPool(2)) {
      var start=new CountDownLatch(1);
      var a=pool.submit(()->{
        start.await();
        try {new TransactionTemplate(manager).executeWithoutResult(tx->business.save(ordinary,writer));return 200;}
        catch(ApiException error) {return error.status();}
      });
      var b=pool.submit(()->{start.await();return settlements.create(request(),writer);});
      start.countDown();ordinaryStatus=a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
    }
    assertTrue(ordinaryStatus==200||ordinaryStatus==409);
    if(ordinaryStatus==409) new TransactionTemplate(manager).executeWithoutResult(tx->business.save(ordinaryBody(prefix+"00000008"),writer));
    var numbers=new java.util.HashSet<String>();
    for(var order:db.state(false).path("orders")) assertTrue(numbers.add(OrderNumbers.display(order)));
    assertEquals(Set.of(prefix+"00000006",prefix+"00000007",prefix+"00000008"),numbers);
  }

  @Test void obsoleteSeparateNumbersMigrateWithoutRewritingAnExistingExternalApproval() throws Exception {
    seedLegacy("审批中");
    var state=(ObjectNode)db.state(false);((ObjectNode)state.path("orders").get(0)).put("orderNumber","JS-2026-11-00000001");
    state.withArray("orders").add(FeishuApprovalFixtures.order().put("id","2026-11-00000006").put("status","草稿"));db.state(state);
    approvals.submit("JS-legacy",mapper.createObjectNode(),writer);
    var snapshot=record("JS-legacy").deepCopy();
    new SettlementOrderNumberMigration(db,manager).run(null);
    assertEquals("2026-11-00000007",currentOrder().path("orderNumber").asText());
    assertEquals(snapshot,record("JS-legacy"));
    assertEquals("JS-legacy",currentOrder().path("id").asText());
  }

  private ObjectNode ordinaryBody(String id) {
    var body=stateBody();
    var order=mapper.createObjectNode().put("id",id).put("type","销售订单").put("status","草稿")
        .put("customerId","c1").put("customer","隔离测试客户").put("owner","销售员").put("total",24).put("paid",0)
        .put("created",CrmRepository.now());
    order.putArray("items").addObject().put("product","p1").put("qty",2).put("price",12);
    ((ObjectNode)body.path("data")).withArray("orders").add(order);
    return body;
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
