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

  @ParameterizedTest @ValueSource(strings={"omit","服务收入","forged-client-value"})
  void aNewReceiptAlwaysUsesTheRealSalesIncomeOptionRegardlessOfTheClientCategory(String requested) throws Exception {
    var template=definition();
    var options=((ObjectNode)template.at("/form/3")).putArray("option");
    options.addObject().put("text","服务收入").put("value","opaque-native-service-v8");
    options.addObject().put("text","销售收入").put("value","opaque-native-sales-v8");
    when(client.definition(CODE)).thenReturn(template);
    String id="automatic-receipt-category";var input=input(id,false);
    if(requested.equals("omit"))input.remove("receiptCategory");else input.put("receiptCategory",requested);
    var result=first.create(input,finance).path("approval");
    assertEquals("PENDING",result.path("status").asText());
    assertEquals("销售收入",result.path("receiptCategory").asText());assertEquals("销售收入",record(id).path("receiptCategory").asText());
    var captured=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
    verify(client).create(eq(CODE),eq("ou_finance"),eq(record(id).path("uuid").asText()),captured.capture());
    JsonNode category=null;
    for(var field:captured.getValue())if(field.path("id").asText().equals("category"))category=field;
    assertNotNull(category);assertEquals("opaque-native-sales-v8",category.path("value").asText());
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());assertTrue(db.documents("receipts",false,0).isEmpty());
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

  @Test void uploadedReceiptVouchersRemainFrozenThroughNativeApprovalBookingAndBackup() throws Exception {
    var assets=new AssetService(db);
    byte[] proof=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=");
    String proofUrl=assets.store(proof);
    byte[] oldImage=proof.clone();oldImage[oldImage.length-1]^=1;
    String oldUrl=assets.store(oldImage);
    var state=db.state(false).deepCopy();
    ((ObjectNode)state.at("/orders/0")).putArray("attachments").addObject().put("name","原订单.png").put("url",oldUrl);
    saveFixtureState(state);
    business=new BusinessService(db,assets);ReflectionTestUtils.setField(business,"feishuReceiptApprovalCode",CODE);
    first=new FeishuReceiptApprovalService(db,client,mapper,business,manager);
    second=new FeishuReceiptApprovalService(db,client,mapper,business,manager);
    var template=definition();((com.fasterxml.jackson.databind.node.ArrayNode)template.path("form"))
        .addObject().put("id","voucher").put("name","附件").put("type","attachmentV2").put("required",false);
    when(client.definition(CODE)).thenReturn(template);
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class))).thenReturn("file-code-1");
    var input=input("receipt-with-proof",true);
    var originals=input.putArray("attachments");originals.addObject().put("name","本次收款单.png").put("url",proofUrl);
    var submitted=first.create(input,finance).path("approval");
    assertEquals("PENDING",submitted.path("status").asText());
    assertEquals(originals,submitted.path("attachments"));
    var pending=record("receipt-with-proof");
    assertEquals(originals,pending.path("attachments"));
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());
    assertTrue(db.documents("receipts",false,0).isEmpty());
    var nativeForm=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
    verify(client).create(eq(CODE),eq("ou_finance"),eq(pending.path("uuid").asText()),nativeForm.capture());
    assertTrue(nativeForm.getValue().toString().contains("file-code-1"));
    assertFalse(nativeForm.getValue().toString().contains(AssetService.PREFIX));
    ((ObjectNode)originals.get(0)).put("name","本地提交后修改.png");
    assertEquals("本次收款单.png",first.create(input,finance).at("/approval/attachments/0/name").asText());
    verify(client,times(1)).uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class));
    state=db.state(false).deepCopy();((ObjectNode)state.at("/orders/0")).remove("attachments");saveFixtureState(state);
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    assertTrue(second.sync("receipt-with-proof",reader).path("approval").path("booked").asBoolean());
    var receipts=db.documents("receipts",false,0);assertEquals(2,receipts.size());
    for(var receipt:receipts)assertEquals(pending.path("attachments"),receipt.path("attachments"));
    assertEquals(100,db.state(false).at("/orders/0/paid").asInt());
    assertEquals(30,db.state(false).at("/orders/1/paid").asInt());
    assertEquals(30,db.state(false).at("/customers/0/balance").asInt());
    var backup=(ObjectNode)mapper.valueToTree(business.backup());
    assertEquals(java.util.Base64.getEncoder().encodeToString(proof),backup.path("assets")
        .path(proofUrl.substring(AssetService.PREFIX.length())).path("data").asText());
    assertTrue(backup.path("assets").has(oldUrl.substring(AssetService.PREFIX.length())),"原订单移除附件后，收款审批冻结快照仍须备份原图");
    var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
    transaction.execute(status->{business.restore(backup,finance);return null;});
    var forged=backup.deepCopy();
    ((ObjectNode)forged.at("/feishuReceiptApprovals/0")).putArray("attachments");
    assertThrows(ApiException.class,()->transaction.execute(status->{business.restore(forged,finance);return null;}));
    assertEquals(pending.path("attachments"),record("receipt-with-proof").path("attachments"));
    assertEquals(100,db.state(false).at("/orders/0/paid").asInt());
  }

  private ObjectNode voucherRequest(String id, int imageCount) throws Exception {
    var assets=new AssetService(db);
    business=new BusinessService(db,assets);ReflectionTestUtils.setField(business,"feishuReceiptApprovalCode",CODE);
    first=new FeishuReceiptApprovalService(db,client,mapper,business,manager);
    second=new FeishuReceiptApprovalService(db,client,mapper,business,manager);
    var template=definition();((com.fasterxml.jackson.databind.node.ArrayNode)template.path("form"))
        .addObject().put("id","voucher").put("name","附件").put("type","attachmentV2").put("required",false);
    when(client.definition(CODE)).thenReturn(template);
    var input=input(id,false);var attachments=input.putArray("attachments");
    byte[] image=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=");
    for(int i=0;i<imageCount;i++) {
      byte[] bytes=image.clone();bytes[bytes.length-1]^=i;
      attachments.addObject().put("name","收款单"+(i+1)+".png").put("url",assets.store(bytes));
    }
    return input;
  }

  private ObjectNode multipleCashInput(String id) {
    jdbc.update("INSERT IGNORE INTO payment_methods(id,name,status) VALUES('pos','POS','启用')");
    var input=input(id,false).put("amount",50);
    ((ObjectNode)input.at("/allocations/0")).put("amount",50);
    var rows=input.putArray("paymentDetails");
    rows.addObject().put("method","银行转账").put("amount",20).put("receiptDate","2026-11-20");
    rows.addObject().put("method","POS").put("amount",30).put("receiptDate","2026-11-25");
    return input;
  }

  @Test void oneApprovalWithSeveralMethodsAndDatesBooksTheFrozenOrderFundingSplitsOnlyOnce() throws Exception {
    String id="multi-method-multi-order";var input=voucherRequest(id,1).put("amount",110);
    jdbc.update("INSERT INTO payment_methods(id,name,status) VALUES('pos','POS','启用')");
    var allocations=input.putArray("allocations");
    allocations.addObject().put("orderId","deposit-1").put("amount",80);
    allocations.addObject().put("orderId","sale-1").put("amount",30);
    var rows=input.putArray("paymentDetails");
    rows.addObject().put("lineNo",999).put("method","银行转账").put("amount",60).put("receiptDate","2026-11-20");
    rows.addObject().put("method","POS").put("amount",50).put("receiptDate","2026-11-25");
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class))).thenReturn("multi-voucher-file");
    var response=first.create(input,finance).path("approval");var pending=record(id);
    assertEquals("PENDING",response.path("status").asText());assertEquals("组合收款",pending.path("method").asText());
    assertEquals("2026-11-25",pending.path("receiptDate").asText());assertEquals(1,pending.at("/paymentDetails/0/lineNo").asInt());
    assertEquals(2,pending.at("/allocations/0/paymentDetails").size());
    assertEquals(60,pending.at("/allocations/0/paymentDetails/0/amount").asInt());
    assertEquals(20,pending.at("/allocations/0/paymentDetails/1/amount").asInt());
    assertEquals(2,pending.at("/allocations/1/paymentDetails/0/lineNo").asInt());
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());assertTrue(db.documents("receipts",false,0).isEmpty());
    var sent=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
    verify(client).create(eq(CODE),eq("ou_finance"),eq(pending.path("uuid").asText()),sent.capture());
    assertTrue(sent.getValue().toString().contains("银行转账"));assertTrue(sent.getValue().toString().contains("POS"));
    assertTrue(sent.getValue().toString().contains("2026-11-20"));assertTrue(sent.getValue().toString().contains("2026-11-25"));
    changeBankConfiguration("rename");jdbc.update("UPDATE payment_methods SET status='停用' WHERE id='pos'");
    var state=db.state(false).deepCopy();((ObjectNode)state.at("/products/0")).put("name","之后改名的商品");saveFixtureState(state);
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    for(var result:concurrently(()->first.sync(id,reader),()->second.sync(id,reader)))
      assertTrue(result.at("/approval/booked").asBoolean(),result.at("/approval/bookingError").asText());
    first.sync(id,reader);second.sync(id,reader);
    var receipts=db.documents("receipts",false,0);assertEquals(3,receipts.size());
    for(var receipt:receipts) {
      int line=receipt.path("paymentLineNo").asInt();String order=receipt.path("order").asText();
      assertEquals(java.util.UUID.nameUUIDFromBytes((id+":"+order+":"+line).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),receipt.path("id").asText());
      assertEquals(line==1?"银行转账":"POS",receipt.path("method").asText());
      assertEquals(line==1?"2026-11-20":"2026-11-25",receipt.path("receivedOn").asText());
      assertEquals(receipt.path("receivedOn"),receipt.path("receiptDate"));
      assertEquals(order.equals("sale-1")?30:line==1?60:20,receipt.path("amount").asInt());
      assertEquals(pending.path("attachments"),receipt.path("attachments"));
      if(order.equals("sale-1"))assertEquals("隔离测试商品",receipt.at("/items/0/name").asText());
    }
    assertEquals(30,db.state(false).at("/orders/0/paid").asInt());assertEquals(80,db.state(false).at("/orders/1/paid").asInt());
    assertEquals(80,db.state(false).at("/customers/0/balance").asInt());assertNoAutomaticShipment();
  }

  @Test void mixedCashAndCreditReserveOnlyTheCreditPartAndExcludeTheirOwnReservationWhenBooking() {
    creditFixture();
    var input=partialInput("mixed-credit-first","sale-1",60,"银行转账");
    var rows=input.putArray("paymentDetails");
    rows.addObject().put("method","银行转账").put("amount",20).put("receiptDate","2026-11-20");
    rows.addObject().put("method","预收款核销").put("amount",40).put("receiptDate","2026-11-25");
    assertEquals("PENDING",first.create(input,finance).at("/approval/status").asText());
    var excessive=partialInput("mixed-credit-too-much","sale-2",50,"银行转账");
    var excessRows=excessive.putArray("paymentDetails");
    excessRows.addObject().put("method","银行转账").put("amount",10).put("receiptDate","2026-11-20");
    excessRows.addObject().put("method","预收款核销").put("amount",40).put("receiptDate","2026-11-25");
    assertTrue(assertThrows(ApiException.class,()->second.create(excessive,finance)).getMessage().contains("余额"));
    assertEquals("PENDING",second.create(partialInput("legacy-credit-second","sale-2",30,"预收款核销"),finance).at("/approval/status").asText());
    assertEquals(70,db.state(false).at("/customers/0/balance").asInt());
    var mixed=record("mixed-credit-first");when(client.instance(mixed.path("instanceCode").asText())).thenReturn(approvedInstance(mixed));
    var booked=first.sync("mixed-credit-first",reader).path("approval");
    assertTrue(booked.path("booked").asBoolean(),booked.path("bookingError").asText());
    assertEquals(30,db.state(false).at("/customers/0/balance").asInt());assertEquals(60,db.state(false).at("/orders/0/paid").asInt());
    var legacy=record("legacy-credit-second");when(client.instance(legacy.path("instanceCode").asText())).thenReturn(approvedInstance(legacy));
    assertTrue(second.sync("legacy-credit-second",reader).at("/approval/booked").asBoolean());
    assertEquals(0,db.state(false).at("/customers/0/balance").asInt());
    var mixedRows=db.documents("receipts",false,0).stream().filter(r->r.path("approvalId").asText().equals("mixed-credit-first")).toList();
    assertEquals(2,mixedRows.size());assertEquals(20,mixedRows.stream().filter(r->r.path("method").asText().equals("银行转账")).findFirst().orElseThrow().path("amount").asInt());
    assertEquals(40,mixedRows.stream().filter(r->r.path("method").asText().equals("预收款核销")).findFirst().orElseThrow().path("amount").asInt());
    assertEquals(4,db.documents("receipts",false,0).size());
  }

  @Test void anInvalidFrozenFundingMatrixCannotPostAnyMoney() {
    String id="invalid-funding-matrix";first.create(multipleCashInput(id),finance);var original=record(id);
    var invalid=original.deepCopy();((ObjectNode)invalid.at("/allocations/0/paymentDetails/0")).put("amount",19);
    jdbc.update("UPDATE feishu_receipt_approvals SET payload=? WHERE id=?",db.encode(invalid),id);
    when(client.instance(invalid.path("instanceCode").asText())).thenReturn(approvedInstance(invalid));
    var result=first.sync(id,reader).path("approval");
    assertEquals("APPROVED",result.path("status").asText());assertFalse(result.path("booked").asBoolean());
    assertFalse(result.path("bookingError").asText().isBlank());
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());assertTrue(db.documents("receipts",false,0).isEmpty());
  }

  @Test void aLaterSplitInsertFailureRollsBackAllEarlierPartsAndTheWholeOrderPayment() {
    String id="split-ledger-rollback";first.create(multipleCashInput(id),finance);var pending=record(id);
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    jdbc.execute("CREATE TRIGGER "+RECEIPT_TRIGGER+" BEFORE INSERT ON receipts FOR EACH ROW BEGIN "
        +"IF JSON_EXTRACT(NEW.payload,'$.paymentLineNo')=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated second split failure'; END IF; END");
    var result=first.sync(id,reader).path("approval");
    assertEquals("APPROVED",result.path("status").asText());assertFalse(result.path("booked").asBoolean());
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());assertTrue(db.documents("receipts",false,0).isEmpty());
    removeFailureTriggers();
    assertTrue(second.sync(id,reader).at("/approval/booked").asBoolean());first.sync(id,reader);
    assertEquals(2,db.documents("receipts",false,0).size());assertEquals(50,db.state(false).at("/orders/0/paid").asInt());
  }

  @Test void anOrdinaryReceiptBeforeTheSettlementPeriodDoesNotInvalidateADifferentAllocatedSettlementPayment() {
    var state=db.state(false).deepCopy();
    ((ObjectNode)state.at("/orders/0")).put("total",120);((ObjectNode)state.at("/orders/0/items/0")).put("price",120);
    var settlement=(ObjectNode)state.at("/orders/1");settlement.put("type","销售订单").put("total",180)
        .put("source",JushuitanDocumentService.SETTLEMENT_SOURCE);
    settlement.putObject("settlement").put("cycle","month").put("periodStart","2026-11-01").put("periodEnd","2026-11-30");
    saveFixtureState(state);
    String id="ordinary-and-settlement-funding-dates";var input=input(id,false).put("amount",300);
    var allocations=input.putArray("allocations");allocations.addObject().put("orderId","sale-1").put("amount",120);
    allocations.addObject().put("orderId","deposit-1").put("amount",180);
    var rows=input.putArray("paymentDetails");
    rows.addObject().put("method","银行转账").put("amount",120).put("receiptDate","2026-10-31");
    rows.addObject().put("method","银行转账").put("amount",180).put("receiptDate","2026-11-02");
    assertEquals("PENDING",first.create(input,finance).at("/approval/status").asText());var pending=record(id);
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    var result=first.sync(id,reader).path("approval");assertTrue(result.path("booked").asBoolean(),result.path("bookingError").asText());
    var receipts=db.documents("receipts",false,0);assertEquals(2,receipts.size());
    assertEquals("2026-10-31",receipts.stream().filter(r->r.path("order").asText().equals("sale-1")).findFirst().orElseThrow().path("receivedOn").asText());
    assertEquals("2026-11-02",receipts.stream().filter(r->r.path("order").asText().equals("deposit-1")).findFirst().orElseThrow().path("receivedOn").asText());
    assertEquals(120,db.state(false).at("/orders/0/paid").asInt());assertEquals(180,db.state(false).at("/orders/1/paid").asInt());
  }

  @Test void uncertainMultilineReceiptsWithoutImagesKeepTheSameNativeFormAndFrozenFundingRowsOnRetry() {
    String id="multiline-unknown-without-images";var input=multipleCashInput(id);
    when(client.create(eq(CODE),eq("ou_finance"),anyString(),any()))
        .thenThrow(mock(FeishuApprovalClient.Uncertain.class)).thenReturn("multiline-recovered-instance");
    assertEquals("UNKNOWN",first.create(input,finance).at("/approval/status").asText());var original=record(id);
    String uuid=original.path("uuid").asText();
    when(client.instance(uuid)).thenThrow(mock(FeishuApprovalClient.NotFound.class));
    jdbc.update("UPDATE payment_methods SET status='停用'");
    ((ObjectNode)input.at("/paymentDetails/0")).put("amount",999);
    assertEquals("PENDING",second.retry(id,finance).at("/approval/status").asText());
    assertEquals(uuid,record(id).path("uuid").asText());assertEquals(original.path("paymentDetails"),record(id).path("paymentDetails"));
    assertEquals(original.path("allocations"),record(id).path("allocations"));
    var forms=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
    verify(client,times(2)).create(eq(CODE),eq("ou_finance"),eq(uuid),forms.capture());
    assertEquals(forms.getAllValues().get(0),forms.getAllValues().get(1));
    verify(client,never()).uploadFile(anyString(),anyString(),anyString(),any(byte[].class));
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());assertTrue(db.documents("receipts",false,0).isEmpty());
  }

  @ParameterizedTest @ValueSource(ints={0,1})
  void failedVoucherUploadRetriesOnlyMissingFilesWithoutCreatingOrBookingAnIncompleteApproval(int alreadyUploaded) throws Exception {
    String id="retry-voucher-files";var input=voucherRequest(id,2);
    var assets=input.path("attachments").deepCopy();var calls=new AtomicInteger();
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class))).thenAnswer(call->{
      if(calls.getAndIncrement()==alreadyUploaded)throw new ApiException("isolated upload failure");
      return call.getArgument(0,String.class).equals("receipt-voucher-1.png")?"file-first":"file-second";
    });
    var failed=first.create(input,finance).path("approval");var original=record(id);String uuid=original.path("uuid").asText();
    assertEquals("FAILED",failed.path("status").asText());assertTrue(failed.path("canRetryOriginal").asBoolean());
    assertEquals(alreadyUploaded,original.path(FeishuApprovalEvidence.FIELD).path("codes").size());
    assertFalse(original.path(FeishuApprovalEvidence.FIELD).path("createAttempted").asBoolean());
    assertEquals(assets,original.path("attachments"));assertEquals(assets,original.path(FeishuApprovalEvidence.FIELD).path("assets"));
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
    assertTrue(db.documents("receipts",false,0).isEmpty());assertEquals(0,db.state(false).at("/orders/0/paid").asInt());
    var retried=second.retry(id,finance).path("approval");var pending=record(id);
    assertEquals("PENDING",retried.path("status").asText());assertEquals(uuid,pending.path("uuid").asText());
    for(String field:List.of("id","approvalCode","openId","amount","method","customerId","receiptDate","receiptCategory","allocations","attachments"))
      assertEquals(original.path(field),pending.path(field),field);
    assertEquals(assets,pending.path(FeishuApprovalEvidence.FIELD).path("assets"));
    assertEquals(mapper.createArrayNode().add("file-first").add("file-second"),pending.path(FeishuApprovalEvidence.FIELD).path("codes"));
    verify(client,times(alreadyUploaded==0?2:1)).uploadFile(eq("receipt-voucher-1.png"),eq("attachment"),eq("image/png"),any(byte[].class));
    verify(client,times(alreadyUploaded==0?1:2)).uploadFile(eq("receipt-voucher-2.png"),eq("attachment"),eq("image/png"),any(byte[].class));
    verify(client,times(1)).create(eq(CODE),eq("ou_finance"),eq(uuid),any(com.fasterxml.jackson.databind.node.ArrayNode.class));
    assertTrue(db.documents("receipts",false,0).isEmpty());assertEquals(0,db.state(false).at("/orders/0/paid").asInt());
  }

  @Test void uncertainReceiptCreationRetriesTheSameUuidAndExactFileCodesWithoutReuploadOrDuplicateBooking() throws Exception {
    String id="uncertain-voucher-create";var input=voucherRequest(id,1);
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class))).thenReturn("file-frozen");
    when(client.create(eq(CODE),eq("ou_finance"),anyString(),any()))
        .thenThrow(mock(FeishuApprovalClient.Uncertain.class)).thenReturn("receipt-recovered-instance");
    var unknown=first.create(input,finance).path("approval");var original=record(id);String uuid=original.path("uuid").asText();
    assertEquals("UNKNOWN",unknown.path("status").asText());assertTrue(unknown.path("canRetryOriginal").asBoolean());
    assertTrue(original.path(FeishuApprovalEvidence.FIELD).path("createAttempted").asBoolean());
    var frozenForm=original.path("form").deepCopy();var evidence=original.path(FeishuApprovalEvidence.FIELD).deepCopy();
    when(client.instance(uuid)).thenThrow(mock(FeishuApprovalClient.NotFound.class));
    var retried=second.retry(id,finance).path("approval");var pending=record(id);
    assertEquals("PENDING",retried.path("status").asText());assertEquals(uuid,pending.path("uuid").asText());
    assertEquals(evidence,pending.path(FeishuApprovalEvidence.FIELD));assertEquals(original.path("attachments"),pending.path("attachments"));
    var forms=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
    verify(client,times(2)).create(eq(CODE),eq("ou_finance"),eq(uuid),forms.capture());
    assertEquals(frozenForm,forms.getAllValues().get(0));assertEquals(frozenForm,forms.getAllValues().get(1));
    var recoveryOrder=inOrder(client);
    recoveryOrder.verify(client).instance(uuid);
    recoveryOrder.verify(client).create(eq(CODE),eq("ou_finance"),eq(uuid),any(com.fasterxml.jackson.databind.node.ArrayNode.class));
    verify(client,times(1)).uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class));
    assertTrue(db.documents("receipts",false,0).isEmpty());assertEquals(0,db.state(false).at("/orders/0/paid").asInt());
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    assertTrue(first.sync(id,reader).path("approval").path("booked").asBoolean());
    assertTrue(second.sync(id,reader).path("approval").path("booked").asBoolean());
    assertEquals(1,db.documents("receipts",false,0).size());
    assertEquals(original.path("attachments"),db.documents("receipts",false,0).getFirst().path("attachments"));
    assertEquals(100,db.state(false).at("/orders/0/paid").asInt());
  }

  @Test void failedVoucherPreparationCannotRetryWhileAnotherReceiptApprovalReservesTheOrder() throws Exception {
    String originalId="failed-voucher-original";var originalInput=voucherRequest(originalId,1);
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class)))
        .thenThrow(new ApiException("isolated upload failure")).thenReturn("file-active");
    assertEquals("FAILED",first.create(originalInput,finance).at("/approval/status").asText());
    var original=record(originalId).deepCopy();
    var activeInput=voucherRequest("replacement-active-receipt",1);
    assertEquals("PENDING",second.create(activeInput,finance).at("/approval/status").asText());
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());
    clearInvocations(client);
    assertThrows(ApiException.class,()->first.retry(originalId,finance));
    assertEquals(original,record(originalId));
    assertEquals("PENDING",record("replacement-active-receipt").path("status").asText());
    verify(client,never()).uploadFile(anyString(),anyString(),anyString(),any(byte[].class));
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
    assertTrue(db.documents("receipts",false,0).isEmpty());
  }

  @ParameterizedTest @ValueSource(strings={"paid","owner","items"})
  void failedVoucherPreparationCannotRetryAfterItsFrozenOrderContentsOrPaidAmountChange(String changedField) throws Exception {
    String id="failed-voucher-stale-order";var input=voucherRequest(id,1).put("amount",40);
    ((ObjectNode)input.at("/allocations/0")).put("amount",40);
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class)))
        .thenThrow(new ApiException("isolated upload failure"));
    assertEquals("FAILED",first.create(input,finance).at("/approval/status").asText());
    var original=record(id).deepCopy();var state=db.state(false).deepCopy();var order=(ObjectNode)state.at("/orders/0");
    switch(changedField) {
      case "paid" -> order.put("paid",10);
      case "owner" -> order.put("owner","其他签单人");
      case "items" -> ((ObjectNode)order.at("/items/0")).put("qty",2).put("price",50);
      default -> fail("Unexpected fixture field");
    }
    saveFixtureState(state);clearInvocations(client);
    assertThrows(ApiException.class,()->second.retry(id,finance));
    assertEquals(original,record(id));assertEquals(state,db.state(false));
    verify(client,never()).uploadFile(anyString(),anyString(),anyString(),any(byte[].class));
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
    assertTrue(db.documents("receipts",false,0).isEmpty());
  }

  private void changeBankConfiguration(String change) {
    var input=mapper.createObjectNode().put("id","bank").put("name",change.equals("rename")?"新收款账户":"银行转账")
        .put("status",change.equals("disable")?"停用":"启用").put("sortOrder",1);
    var admin=new Actor("admin","管理员",Set.of("system.manage"));
    new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->
        new PaymentMethodService(db).save(input,admin));
  }

  @ParameterizedTest @ValueSource(strings={"rename","disable"})
  void aVerifiedPendingReceiptBooksItsFrozenMethodAfterTheConfigurationChanges(String change) {
    String id="pending-before-method-change";
    var input=input(id,false).put("amount",40);((ObjectNode)input.at("/allocations/0")).put("amount",40);
    assertEquals("PENDING",first.create(input,finance).at("/approval/status").asText());
    var pending=record(id);changeBankConfiguration(change);
    assertEquals("银行转账",record(id).path("method").asText());
    when(client.instance(pending.path("instanceCode").asText())).thenReturn(approvedInstance(pending));
    var approved=second.sync(id,reader).path("approval");
    assertTrue(approved.path("booked").asBoolean(),approved.path("bookingError").asText());
    var ledger=db.documents("receipts",false,0);assertEquals(1,ledger.size());
    assertEquals("银行转账",ledger.getFirst().path("method").asText());assertEquals(40,ledger.getFirst().path("amount").asInt());
    assertEquals(40,db.state(false).at("/orders/0/paid").asInt());
    var next=input("new-old-method",false).put("amount",20);((ObjectNode)next.at("/allocations/0")).put("amount",20);
    clearInvocations(client);
    assertThrows(ApiException.class,()->first.create(next,finance));
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
    assertEquals(1,db.documents("receipts",false,0).size());assertEquals(40,db.state(false).at("/orders/0/paid").asInt());
  }

  @ParameterizedTest @ValueSource(strings={"rename","disable"})
  void anUnsentFailedReceiptMustRevalidateCurrentEnabledMethodsBeforeResumingItsFiles(String change) throws Exception {
    String id="method-changed-during-file-failure";var input=voucherRequest(id,1);
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class)))
        .thenThrow(new ApiException("isolated file failure"));
    assertEquals("FAILED",first.create(input,finance).at("/approval/status").asText());
    var failed=record(id).deepCopy();changeBankConfiguration(change);clearInvocations(client);
    assertThrows(ApiException.class,()->second.retry(id,finance));
    assertEquals(failed,record(id));
    verify(client,never()).uploadFile(anyString(),anyString(),anyString(),any(byte[].class));
    verify(client,never()).create(anyString(),anyString(),anyString(),any());
    assertTrue(db.documents("receipts",false,0).isEmpty());assertEquals(0,db.state(false).at("/orders/0/paid").asInt());
  }

  @Test void legacyFailedVoucherPreparationKeepsItsOriginalCategoryAndOpaqueFormWhenResumed() throws Exception {
    String id="legacy-category-file-retry";var input=voucherRequest(id,1);
    when(client.uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class)))
        .thenThrow(new ApiException("isolated upload failure"));
    assertEquals("FAILED",first.create(input,finance).at("/approval/status").asText());
    var legacy=record(id);legacy.put("receiptCategory","服务收入");
    for(var field:legacy.path("form"))if(field.path("id").asText().equals("category"))
      ((ObjectNode)field).put("value","legacy-opaque-service-v1");
    jdbc.update("UPDATE feishu_receipt_approvals SET payload=? WHERE id=?",db.encode(legacy),id);
    var original=record(id);String uuid=original.path("uuid").asText();
    var expectedForm=(com.fasterxml.jackson.databind.node.ArrayNode)original.path("form").deepCopy();
    for(var field:expectedForm)if(field.path("id").asText().equals("voucher"))
      ((ObjectNode)field).putArray("value").add("file-legacy-preserved");
    doReturn("file-legacy-preserved").when(client).uploadFile(anyString(),eq("attachment"),eq("image/png"),any(byte[].class));
    clearInvocations(client);
    when(client.definition(anyString())).thenThrow(new ApiException("must not rebuild the original template"));
    var resumed=second.retry(id,finance).path("approval");
    assertEquals("PENDING",resumed.path("status").asText());assertEquals("服务收入",resumed.path("receiptCategory").asText());
    assertEquals(uuid,record(id).path("uuid").asText());assertEquals("服务收入",record(id).path("receiptCategory").asText());
    var captured=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
    verify(client).create(eq(CODE),eq("ou_finance"),eq(uuid),captured.capture());assertEquals(expectedForm,captured.getValue());
    assertEquals(original.path("attachments"),record(id).path("attachments"));
    verify(client,never()).definition(anyString());
    assertEquals(0,db.state(false).at("/orders/0/paid").asInt());assertTrue(db.documents("receipts",false,0).isEmpty());
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
