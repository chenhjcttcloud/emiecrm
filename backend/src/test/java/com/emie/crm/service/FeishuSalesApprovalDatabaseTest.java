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
import java.lang.reflect.Constructor;
import java.util.List;
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
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;

/** Requires an explicitly started disposable database; never reads application credentials or URLs. */
@EnabledIfEnvironmentVariable(named="CRM_ORDER_APPROVAL_DB_TEST", matches="1")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class FeishuSalesApprovalDatabaseTest {
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
  private AsyncTaskExecutor executor;

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
    var assets = mock(AssetService.class);
    when(assets.backup(any())).thenReturn(java.util.Map.of());
    business = new BusinessService(db, assets);
    client = mock(FeishuApprovalClient.class);
    when(client.identityReady()).thenReturn(true);
    when(client.enabled(anyString())).thenReturn(true);
    when(client.ready(anyString())).thenReturn(true);
    when(client.appId()).thenReturn("fixture-app");
    when(client.approvalCode(anyString())).thenReturn("fixture-template");
    when(client.definition("fixture-template")).thenReturn(FeishuApprovalFixtures.definition());
    when(client.create(eq("fixture-template"), eq("ou_sales"), anyString(), any(ArrayNode.class))).thenReturn("fixture-instance");
    executor = mock(AsyncTaskExecutor.class);
    doAnswer(call -> {workers.add(call.getArgument(0, Runnable.class)); return null;}).when(executor).execute(any(Runnable.class));
    service = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor);
  }

  @Test void orderAndQueueCommitBeforeAnyExternalCreate() throws Exception {
    var response = service.createAndSubmit("o1", request(), writer);
    assertEquals("QUEUED", response.path("approval").path("status").asText());
    assertEquals("审批中", currentOrder().path("status").asText());
    assertFalse(record().path("uuid").asText().isBlank());
    verify(client, never()).create(any(), any(), any(), any());
    var savedUuid = record().path("uuid").asText();
    runWorkers();
    assertEquals("PENDING", record().path("status").asText());
    verify(client).create(eq("fixture-template"), eq("ou_sales"), eq(savedUuid), any(ArrayNode.class));
  }

  @Test void invalidTemplateRollsBackOrderAuditAndQueueTogether() throws Exception {
    when(client.definition("fixture-template")).thenReturn(mapper.createObjectNode().putArray("form").addObject()
        .put("id", "unsupported").put("name", "不支持字段").put("type", "radioV2").put("required", true));
    var before = db.state(false).deepCopy();
    assertThrows(ApiException.class, () -> service.createAndSubmit("o1", request(), writer));
    assertEquals(before, db.state(false));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit", Integer.class));
    assertTrue(workers.isEmpty());
  }

  @Test void plannedWarehouseUsesTrustedNameAndFrozenRetriesRetainTheOriginalUuid() throws Exception {
    var directory = mock(JushuitanService.class);
    when(directory.warehouses()).thenReturn(mapper.readTree("[{\"id\":5,\"name\":\"可信发货仓\"}]"));
    service = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor, directory);
    var input = warehouseRequest();
    var frozenInput = input.deepCopy();
    service.createAndSubmit("o1", input, writer);
    var queued = record();
    assertEquals("可信发货仓", currentOrder().path("approvalDraft").path("warehouseName").asText());
    assertEquals("可信发货仓", queued.path("orderSnapshot").path("approvalDraft").path("warehouseName").asText());
    assertTrue(queued.path("form").toString().contains("计划发货仓库：可信发货仓（5）"));
    assertEquals(frozenInput, input);
    assertEquals("QUEUED", service.createAndSubmit("o1", frozenInput, writer).path("approval").path("status").asText());
    Constructor<FeishuApprovalClient.Uncertain> constructor = FeishuApprovalClient.Uncertain.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    when(client.create(any(), any(), any(), any())).thenThrow(constructor.newInstance());
    runWorkers();
    assertEquals("UNKNOWN", service.createAndSubmit("o1", frozenInput, writer).path("approval").path("status").asText());
    assertEquals(queued.path("uuid"), record().path("uuid"));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jst_shipments", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jst_document_sales", Integer.class));
    verify(directory, times(1)).warehouses();
    verify(client, times(1)).create(any(), any(), any(), any());
    verify(executor, times(1)).execute(any(Runnable.class));
    var changed = stateBody(); order(changed.path("data")).withObject("approvalDraft").put("warehouseId", "6");
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(changed, writer)));
  }

  @Test void failedWarehouseDirectoryLeavesBothCreationAndSavedDraftSubmissionUntouched() throws Exception {
    var directory = mock(JushuitanService.class);
    when(directory.warehouses()).thenThrow(new ApiException(503, "仓库目录暂不可用"));
    service = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor, directory);
    var input = warehouseRequest();
    var before = db.state(false).deepCopy();
    assertThrows(ApiException.class, () -> service.createAndSubmit("o1", input, writer));
    assertEquals(before, db.state(false));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit", Integer.class));
    order(input.path("data")).put("status", "草稿");
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(input, writer));
    before = db.state(false).deepCopy();
    int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit", Integer.class);
    assertThrows(ApiException.class, () -> service.submit("o1", input.path("approval"), writer));
    assertEquals(before, db.state(false));
    assertEquals(audits, jdbc.queryForObject("SELECT COUNT(*) FROM audit", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    assertTrue(workers.isEmpty());
    verify(client, never()).definition(any());
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void savedDraftWarehouseNormalizationRollsBackOnFormFailureAndLocksAfterSubmission() throws Exception {
    var directory = mock(JushuitanService.class);
    when(directory.warehouses()).thenReturn(mapper.readTree("[{\"id\":5,\"name\":\"可信发货仓\"}]"));
    service = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor, directory);
    var input = warehouseRequest(); order(input.path("data")).put("status", "草稿");
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(input, writer));
    var before = db.state(false).deepCopy();
    int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit", Integer.class);
    var noNote = FeishuApprovalFixtures.definition(); ((ArrayNode) noNote.path("form")).remove(4);
    when(client.definition("fixture-template")).thenReturn(noNote);
    assertThrows(ApiException.class, () -> service.submit("o1", input.path("approval"), writer));
    assertEquals(before, db.state(false));
    assertEquals(audits, jdbc.queryForObject("SELECT COUNT(*) FROM audit", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    verify(client, never()).create(any(), any(), any(), any());
    when(client.definition("fixture-template")).thenReturn(FeishuApprovalFixtures.definition());
    assertEquals("PENDING", service.submit("o1", input.path("approval"), writer).path("status").asText());
    assertEquals("可信发货仓", currentOrder().path("approvalDraft").path("warehouseName").asText());
    var originalUuid = record().path("uuid");
    when(directory.warehouses()).thenThrow(new ApiException(503, "此时不应再读取仓库目录"));
    assertEquals("PENDING", service.submit("o1", input.path("approval"), writer).path("status").asText());
    assertEquals(originalUuid, record().path("uuid"));
    verify(directory, times(2)).warehouses();
    verify(client, times(1)).create(any(), any(), any(), any());
    var backup = mapper.valueToTree(business.backup());
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.restore(backup, writer));
    order(backup.path("state")).withObject("approvalDraft").put("warehouseId", "6");
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.restore(backup, writer)));
    assertEquals("5", currentOrder().path("approvalDraft").path("warehouseId").asText());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jst_shipments", Integer.class));
  }

  @Test void concurrentIdenticalSubmissionsSaveAndDispatchExactlyOnce() throws Exception {
    var input = request();
    var other = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor);
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var one = threads.submit(() -> {assertTrue(start.await(10, TimeUnit.SECONDS)); return service.createAndSubmit("o1", input, writer);});
      var two = threads.submit(() -> {assertTrue(start.await(10, TimeUnit.SECONDS)); return other.createAndSubmit("o1", input, writer);});
      start.countDown();
      assertEquals("QUEUED", one.get(25, TimeUnit.SECONDS).path("approval").path("status").asText());
      assertEquals("QUEUED", two.get(25, TimeUnit.SECONDS).path("approval").path("status").asText());
    }
    assertEquals(2, db.state(false).path("revision").asInt());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    runWorkers();
    verify(client, times(1)).create(any(), any(), any(), any());
  }

  @Test void concurrentDifferentRequestsWithOneOrderNumberRejectTheCollision() throws Exception {
    var input = request();
    var competing = input.deepCopy();
    order(competing.path("data")).put("approvalRequestId", "another-frozen-request").put("note", "另一笔订单");
    var other = new FeishuSalesApprovalService(db, client, mapper, manager, business, executor);
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var one = threads.submit(() -> {assertTrue(start.await(10, TimeUnit.SECONDS)); return submitOrConflict(service, input);});
      var two = threads.submit(() -> {assertTrue(start.await(10, TimeUnit.SECONDS)); return submitOrConflict(other, competing);});
      start.countDown();
      var outcomes = List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
      assertTrue(outcomes.contains("QUEUED")); assertTrue(outcomes.contains("409"));
    }
    assertEquals(2, db.state(false).path("revision").asInt());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    runWorkers();
    verify(client, times(1)).create(any(), any(), any(), any());
  }

  private String submitOrConflict(FeishuSalesApprovalService target, JsonNode input) {
    try { return target.createAndSubmit("o1", input, writer).path("approval").path("status").asText(); }
    catch (ApiException conflict) { return Integer.toString(conflict.status()); }
  }

  @Test void lostCreateResponseRemainsLockedAndRecoversByOriginalUuid() throws Exception {
    Constructor<FeishuApprovalClient.Uncertain> constructor = FeishuApprovalClient.Uncertain.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    when(client.create(any(), any(), any(), any())).thenThrow(constructor.newInstance());
    service.createAndSubmit("o1", request(), writer); runWorkers();
    var unknown = record();
    assertEquals("UNKNOWN", unknown.path("status").asText());
    assertTrue(unknown.path("form").isArray());
    var changed = stateBody(); order(changed.path("data")).put("total", 25);
    assertThrows(ApiException.class, () -> business.save(changed, writer));
    when(client.instance(unknown.path("uuid").asText())).thenReturn(instance(unknown, "APPROVED"));
    service.sync("o1", writer);
    assertEquals("已批准", currentOrder().path("status").asText());
    assertEquals("APPROVED", record().path("status").asText());
    verify(client, times(1)).create(any(), any(), any(), any());
  }

  @Test void mismatchedTerminalInstanceCannotApproveAndDoesNotMutateState() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    var before = db.state(false).deepCopy();
    when(client.instance("fixture-instance")).thenReturn(instance(record(), "APPROVED").put("approval_code", "other-template"));
    assertThrows(ApiException.class, () -> service.sync("o1", writer));
    assertEquals(before, db.state(false));
    assertEquals("PENDING", record().path("status").asText());
  }

  @Test void rejectedOrderCanBeEditedAndResubmittedWithVisibleHistory() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    var first = record();
    when(client.instance("fixture-instance")).thenReturn(instance(first, "REJECTED"));
    var rejected = service.sync("o1", writer);
    assertEquals("草稿", currentOrder().path("status").asText());
    var retry = stateBody();
    order(retry.path("data")).put("note", "修改后重新提交").put("status", "审批中");
    retry.putObject("approval").put("retryVersion", rejected.path("retryVersion").asText());
    var response = service.createAndSubmit("o1", retry, writer);
    assertEquals("REJECTED", response.path("approval").path("history").get(0).path("status").asText());
    assertEquals("fixture-instance", response.path("approval").path("history").get(0).path("instanceCode").asText());
    assertNotEquals(first.path("uuid"), record().path("uuid"));
    runWorkers();
    when(client.instance("fixture-instance")).thenReturn(instance(record(), "REJECTED"));
    service.sync("o1", writer);
    assertEquals(409, assertThrows(ApiException.class, () -> service.submit("o1",
        mapper.createObjectNode().put("retryVersion", first.path("uuid").asText()), writer)).status());
  }

  @Test void draftsStayLocalAndManualApprovalCannotBypassTheRequiredRoute() throws Exception {
    var draft = request(); order(draft.path("data")).put("status", "草稿");
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(draft, writer));
    assertEquals("草稿", currentOrder().path("status").asText());
    assertTrue(workers.isEmpty()); verifyNoInteractions(client);
    var manual = stateBody(); order(manual.path("data")).put("status", "已批准");
    assertTrue(assertThrows(ApiException.class, () -> business.save(manual, writer)).getMessage().contains("飞书"));
    order(manual.path("data")).put("status", "审批中");
    assertThrows(ApiException.class, () -> business.save(manual, writer));
  }

  @ParameterizedTest @ValueSource(strings={"销售订单", "海外提货单", "预收款订单"})
  void missingDefinitionsCannotEnableLocalApprovalOrForgedBackupApproval(String type) throws Exception {
    when(client.enabled()).thenReturn(false);
    when(client.enabled(anyString())).thenReturn(false);
    when(client.ready(anyString())).thenReturn(false);
    var settings = service.config(writer);
    assertEquals(true, settings.get("required"));
    assertEquals(false, settings.get("canSubmit"));
    var draft = request(); order(draft.path("data")).put("type", type).put("status", "草稿");
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(draft, writer));
    var before = db.state(false).deepCopy();
    for (String status : List.of("审批中", "已批准")) {
      var manual = stateBody(); order(manual.path("data")).put("status", status);
      assertTrue(assertThrows(ApiException.class, () -> new TransactionTemplate(manager)
          .executeWithoutResult(tx -> business.save(manual, writer))).getMessage().contains("飞书"));
      assertEquals(before, db.state(false));
    }
    ObjectNode backup = mapper.valueToTree(business.backup());
    order(backup.path("state")).put("status", "已批准");
    assertTrue(assertThrows(ApiException.class, () -> new TransactionTemplate(manager)
        .executeWithoutResult(tx -> business.restore(backup, writer))).getMessage().contains("飞书"));
    assertEquals(before, db.state(false));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    verify(client, never()).definition(anyString()); verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void pendingOrderAllowsNotesButLocksCoreAndBackupCannotLoseItsApproval() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    var body = stateBody(); order(body.path("data")).put("note", "补充备注");
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(body, writer));
    assertEquals("补充备注", currentOrder().path("note").asText());
    var core = stateBody(); order(core.path("data")).put("owner", "另一个人");
    assertThrows(ApiException.class, () -> business.save(core, writer));
    var backup = mapper.valueToTree(business.backup());
    ((ObjectNode) backup).remove("feishuApprovals");
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.restore(backup, writer)));
    assertEquals("PENDING", record().path("status").asText());
  }

  @Test void actualSalesTemplateRejectsMissingRecipientWithoutSavingTheOrder() throws Exception {
    when(client.definition("fixture-template")).thenReturn(FeishuDomesticApprovalTemplateTest.definition("sales"));
    var input = request(); input.putObject("approval").putArray("discounts").add("0");
    var before = db.state(false).deepCopy();
    assertThrows(ApiException.class, () -> service.createAndSubmit("o1", input, writer));
    assertEquals(before, db.state(false));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    assertTrue(workers.isEmpty());
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void pickupOrdersFollowTheSameRequiredSalesTemplateAndVerifiedApprovalRoute() throws Exception {
    when(client.definition("fixture-template")).thenReturn(FeishuDomesticApprovalTemplateTest.definition("sales"));
    var input = request(); order(input.path("data")).put("type", "海外提货单");
    input.set("approval", FeishuDomesticApprovalTemplateTest.details());
    var manual = input.deepCopy(); order(manual.path("data")).put("status", "已批准");
    assertThrows(ApiException.class, () -> business.save(manual, writer));
    service.createAndSubmit("o1", input, writer); runWorkers();
    when(client.instance("fixture-instance")).thenReturn(instance(record(), "APPROVED"));
    service.sync("o1", writer);
    assertEquals("已批准", currentOrder().path("status").asText());
    assertEquals("海外提货单", record().path("orderType").asText());
    assertEquals(24, currentOrder().path("total").asInt());
    verify(client).approvalCode("海外提货单");
  }

  @Test void actualPrepaymentTemplateUsesTheBoundOwnerAndDoesNotRecordAReceipt() throws Exception {
    when(client.definition("fixture-template")).thenReturn(FeishuDomesticApprovalTemplateTest.definition("prepayment"));
    jdbc.update("INSERT INTO user_profiles(name,display_name,status) VALUES('sales','销售员','启用')");
    var input = request(); order(input.path("data")).put("type", "预收款订单").put("total", 60).putArray("items");
    input.putObject("approval").put("receiptDate", "2026-11-23").put("receiptCategory", "销售收入");
    service.createAndSubmit("o1", input, writer); runWorkers();
    when(client.instance("fixture-instance")).thenReturn(instance(record(), "APPROVED"));
    service.sync("o1", writer);
    assertEquals("已批准", currentOrder().path("status").asText());
    assertEquals(60, currentOrder().path("total").asInt());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class));
    assertTrue(record().path("orderSnapshot").path("items").isEmpty());
  }

  @Test void schedulerResumesExpiredClaimsUsingThePersistedUuid() throws Exception {
    service.createAndSubmit("o1", request(), writer);
    var interrupted = record().put("status", "SUBMITTING").put("attempt", "dead-worker").put("leaseUntil", 1);
    jdbc.update("UPDATE feishu_order_approvals SET status='SUBMITTING',payload=? WHERE order_id='o1'", interrupted.toString());
    workers.clear();
    service.poll();
    assertEquals("PENDING", record().path("status").asText());
    verify(client).create(eq("fixture-template"), eq("ou_sales"), eq(interrupted.path("uuid").asText()), any(ArrayNode.class));
  }

  @Test void queuedCancellationAndSoftDeletionKeepHistoryAndPreventTheWorkerFromSending() throws Exception {
    service.createAndSubmit("o1", request(), writer);
    var staleBackup = mapper.valueToTree(business.backup());
    assertEquals("已取消", service.cancel("o1", writer).path("state").path("orders").get(0).path("status").asText());
    runWorkers(); verify(client, never()).create(any(), any(), any(), any());
    var deleted = new TransactionTemplate(manager).execute(tx -> business.deleteOrder("o1", writer));
    assertTrue(order(deleted).hasNonNull("deletedAt"));
    assertEquals("CANCELED", record().path("status").asText());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM feishu_order_approvals", Integer.class));
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> business.restore(staleBackup, writer)));
    var revived = stateBody(); order(revived.path("data")).remove("deletedAt"); order(revived.path("data")).put("status", "草稿");
    assertThrows(ApiException.class, () -> business.save(revived, writer));
  }

  @Test void onlyTheInitiatorCanWithdrawAndTheConfirmedTerminalStateAllowsSoftDeletion() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    jdbc.update("INSERT INTO feishu_identities(app_id,open_id,user_name) VALUES('fixture-app','ou_other','other')");
    var other = new Actor("other", "销售", Set.of("business.read", "business.write"));
    assertEquals(403, assertThrows(ApiException.class, () -> service.cancel("o1", other)).status());
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).execute(tx -> business.deleteOrder("o1", writer)));
    var original = record();
    when(client.instance("fixture-instance")).thenReturn(instance(original, "PENDING"), instance(original, "CANCELED"));
    service.cancel("o1", writer);
    verify(client).cancel("fixture-template", "fixture-instance", "ou_sales");
    assertEquals("已取消", currentOrder().path("status").asText());
    var removed = new TransactionTemplate(manager).execute(tx -> business.deleteOrder("o1", writer));
    assertTrue(order(removed).hasNonNull("deletedAt"));
    assertEquals("fixture-instance", record().path("instanceCode").asText());
  }

  @Test void lostWithdrawalResponseIsReconciledWithoutASecondCancellation() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    var original = record(); when(client.instance("fixture-instance")).thenReturn(instance(original, "PENDING"));
    doThrow(new ApiException(502, "撤回结果待确认")).when(client).cancel(any(), any(), any());
    assertThrows(ApiException.class, () -> service.cancel("o1", writer));
    assertTrue(record().hasNonNull("cancelRequestedBy")); assertEquals("审批中", currentOrder().path("status").asText());
    when(client.instance("fixture-instance")).thenReturn(instance(original, "CANCELED"));
    service.sync("o1", writer);
    assertEquals("已取消", currentOrder().path("status").asText());
    assertFalse(record().hasNonNull("cancelRequestedBy"));
    verify(client, times(1)).cancel(any(), any(), any());
  }

  @Test void anAssociationAppearingDuringWithdrawalKeepsTheLinkedOrderUncanceled() throws Exception {
    service.createAndSubmit("o1", request(), writer); runWorkers();
    var original = record(); when(client.instance("fixture-instance")).thenReturn(instance(original, "PENDING"), instance(original, "CANCELED"));
    doAnswer(call -> {jdbc.update("INSERT INTO jst_document_sales(environment,order_id,shop_id,o_id,so_id) VALUES('production','o1','1','111','FIXTURE-SO')"); return null;})
        .when(client).cancel(any(), any(), any());
    assertEquals(409, assertThrows(ApiException.class, () -> service.cancel("o1", writer)).status());
    assertEquals("草稿", currentOrder().path("status").asText());
    assertEquals("CANCELED", record().path("status").asText());
    assertTrue(record().path("message").asText().contains("聚水潭关联"));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM jst_document_sales", Integer.class));
  }

  @Test void existingFinancialHistoryBlocksCancellationDespiteAZeroNetBalance() throws Exception {
    var draft = request(); order(draft.path("data")).put("status", "草稿");
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(draft, writer));
    jdbc.update("INSERT INTO receipts(id,payload) VALUES('payment',?)", mapper.createObjectNode().put("order", "o1").put("amount", 10).put("type", "销售订单").toString());
    jdbc.update("INSERT INTO receipts(id,payload) VALUES('refund',?)", mapper.createObjectNode().put("order", "o1").put("amount", -10).put("type", "订单变更").toString());
    assertThrows(ApiException.class, () -> service.cancel("o1", writer));
    assertThrows(ApiException.class, () -> new TransactionTemplate(manager).execute(tx -> business.deleteOrder("o1", writer)));
    assertEquals("草稿", currentOrder().path("status").asText());
    verify(client, never()).cancel(any(), any(), any());
  }

  @Test void optionalPrepaymentProductsStayIndependentAndNewPaymentsSaveSnapshots() throws Exception {
    jdbc.update("INSERT INTO payment_methods(id,name,status) VALUES('bank','银行转账','启用')");
    var request = request(); order(request.path("data")).put("type", "预收款订单").put("total", 60);
    service.createAndSubmit("o1", request, writer); runWorkers();
    when(client.instance("fixture-instance")).thenReturn(instance(record(), "APPROVED")); service.sync("o1", writer);
    var payment = stateBody(); order(payment.path("data")).put("paid", 20).put("method", "银行转账");
    var finance = new Actor("finance", "财务", Set.of("business.read", "business.write", "payment.write"));
    new TransactionTemplate(manager).executeWithoutResult(tx -> business.save(payment, finance));
    assertEquals(60, currentOrder().path("total").asInt()); assertEquals(20, currentOrder().path("paid").asInt());
    assertEquals(20, db.state(false).path("customers").get(0).path("balance").asInt());
    var receipt = db.parse(jdbc.queryForObject("SELECT payload FROM receipts", String.class));
    assertEquals("snapshot", receipt.path("itemsSource").asText());
    assertEquals("隔离商品", receipt.path("items").get(0).path("name").asText());
    var state = (ObjectNode) db.state(false); ((ObjectNode) state.path("products").get(0)).put("name", "同步后的新商品名"); db.state(state);
    assertEquals("隔离商品", business.receipts().get(0).path("items").get(0).path("name").asText());
  }

  private ObjectNode request() throws Exception {
    var result = stateBody(); result.withObject("data").withArray("orders").add(FeishuApprovalFixtures.order());
    result.putObject("approval"); return result;
  }
  private ObjectNode warehouseRequest() throws Exception {
    var input = request();
    order(input.path("data")).putObject("approvalDraft").put("warehouseId", "5").put("warehouseName", "伪造仓库名称");
    input.withObject("approval").put("warehouseId", "5").put("warehouseName", "已过时的名称");
    return input;
  }
  private ObjectNode stateBody() {
    var state = db.state(false); var body = mapper.createObjectNode().put("revision", state.path("revision").asLong());
    body.set("data", state.deepCopy()); return body;
  }
  private ObjectNode order(JsonNode state) { return (ObjectNode) state.path("orders").get(0); }
  private ObjectNode currentOrder() { return order(db.state(false)); }
  private ObjectNode record() {
    return (ObjectNode) db.parse(jdbc.queryForObject("SELECT payload FROM feishu_order_approvals WHERE order_id='o1'", String.class));
  }
  private ObjectNode instance(JsonNode record, String status) {
    return mapper.createObjectNode().put("approval_code", record.path("approvalCode").asText())
        .put("open_id", record.path("openId").asText()).put("uuid", record.path("uuid").asText())
        .put("instance_code", "fixture-instance").put("status", status);
  }
  private void runWorkers() { for (Runnable task; (task = workers.poll()) != null;) task.run(); }
}
