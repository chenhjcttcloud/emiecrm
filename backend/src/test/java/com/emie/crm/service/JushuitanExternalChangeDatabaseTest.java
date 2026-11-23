package com.emie.crm.service;

import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in against a disposable MySQL container, never the CRM application database. */
@EnabledIfEnvironmentVariable(named="CRM_EXTERNAL_DIFF_DB_TEST",matches="1")
class JushuitanExternalChangeDatabaseTest {
 final ObjectMapper m=new ObjectMapper(); JdbcTemplate jdbc; CrmRepository db; JushuitanClient client;
 DataSourceTransactionManager manager;JushuitanService service;ObjectNode original,actual;
 final Actor finance=new Actor("finance","财务测试",Set.of("business.write","order.approve","payment.write"));
 boolean failAudit;
 @BeforeEach void setup()throws Exception{
  var source=new DriverManagerDataSource("jdbc:mysql://emiecrm-external-fixture:3306/emiecrm_external_diff_test?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8","root","fixture-only");
  jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
  for(String table:List.of("state","receipts","audit","audit_archives","jst_shipments","payment_methods","feishu_order_approvals","feishu_receipt_approvals"))jdbc.execute("DROP TABLE IF EXISTS "+table);
  jdbc.execute("CREATE TABLE state(id INT PRIMARY KEY,payload LONGTEXT)");
  jdbc.execute("CREATE TABLE receipts(seq BIGINT AUTO_INCREMENT PRIMARY KEY,id VARCHAR(191) UNIQUE,payload LONGTEXT)");
  jdbc.execute("CREATE TABLE audit(seq BIGINT AUTO_INCREMENT PRIMARY KEY,id VARCHAR(191) UNIQUE,payload LONGTEXT)");
  jdbc.execute("CREATE TABLE jst_shipments(order_id VARCHAR(191) PRIMARY KEY,payload LONGTEXT,updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3))");
  jdbc.execute("CREATE TABLE payment_methods(id VARCHAR(191) PRIMARY KEY,name VARCHAR(191),status VARCHAR(20))");
  // BusinessService.save checks both approval locks even when this fixture has no approval history.
  jdbc.execute("CREATE TABLE feishu_order_approvals(order_id VARCHAR(191) PRIMARY KEY,status VARCHAR(16) NOT NULL,payload LONGTEXT NOT NULL)");
  jdbc.execute("CREATE TABLE feishu_receipt_approvals(id VARCHAR(64) PRIMARY KEY,status VARCHAR(16) NOT NULL,payload LONGTEXT NOT NULL)");
  jdbc.execute("CREATE TABLE audit_archives(id VARCHAR(64) PRIMARY KEY,quarter VARCHAR(7),record_count INT,raw_bytes INT,compressed_bytes INT,sha256 CHAR(64),content LONGBLOB,created_at VARCHAR(32))");
  db=new CrmRepository(jdbc,m){@Override public void audit(String user,String action){if(failAudit&&action.startsWith("确认聚水潭外部变更"))throw new IllegalStateException("simulated audit persistence failure");super.audit(user,action);}};
  original=(ObjectNode)m.readTree("""
   {"customers":[{"id":"c1","name":"隔离测试"}],"products":[{"id":"p1","source":"jushuitan","jstEnvironment":"sandbox","jstSku":"A","enabled":true}],"orders":[{"id":"o1","customerId":"c1","customer":"隔离测试","status":"已批准","type":"销售订单","total":20,"paid":20,"items":[{"product":"p1","qty":2,"price":10}]}]}
   """);
  jdbc.update("INSERT INTO state VALUES(1,?)",db.encode(original));jdbc.update("INSERT INTO payment_methods VALUES('bank','银行转账','启用')");
  var request=(ObjectNode)m.readTree("""
   {"shop_id":1,"so_id":"CRM-fixture","wms_co_id":5,"pay_amount":20,"freight":0,"remark":"原备注","items":[{"sku_id":"A","qty":2,"price":10,"amount":20}]}
   """);
  var record=m.createObjectNode().put("orderId","o1").put("environment","sandbox").put("soId","CRM-fixture").put("oId",123)
   .put("status","ACCEPTED").put("updatedAt",CrmRepository.now()).put("editVersion","fixture-version");
  record.set("request",request);record.set("snapshot",original.path("orders").get(0));jdbc.update("INSERT INTO jst_shipments(order_id,payload) VALUES('o1',?)",db.encode(record));
  actual=request.deepCopy();actual.put("o_id",123).put("status","WaitConfirm").put("pay_amount",30);((ObjectNode)actual.path("items").get(0)).put("qty",3).put("amount",30);
  client=mock(JushuitanClient.class);
  when(client.query(eq("/open/orders/single/query"),any())).thenAnswer(i->m.createObjectNode().put("code",0).set("data",m.createObjectNode().set("orders",m.createArrayNode().add(actual.deepCopy()))));
  when(client.query(eq("/open/orders/out/simple/query"),any())).thenAnswer(i->m.createObjectNode().put("code",0).set("data",m.createObjectNode().set("datas",m.createArrayNode())));
  service=new JushuitanService(db,client,m,manager,"sandbox");
 }
 ObjectNode input(){
  var record=service.sync("o1");var diff=record.path("externalDifference");
  return m.createObjectNode().put("id",diff.path("id").asText()).put("fingerprint",diff.path("fingerprint").asText())
   .put("version",record.path("editVersion").asText()).put("reason","隔离数据库核对").put("amount",10).put("method","银行转账").put("reference","FIXTURE-BANK");
 }
 JsonNode record(){return db.parse(jdbc.queryForObject("SELECT payload FROM jst_shipments WHERE order_id='o1'",String.class));}
 @Test void receiptAndConfirmationRollbackTogetherAndRetryRecordsOnce(){
  var input=input();failAudit=true;
  assertThrows(IllegalStateException.class,()->service.confirmExternal("o1",input,finance));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM receipts",Integer.class));
  assertTrue(record().has("externalDifference"));assertFalse(record().has("effectiveOrder"));
  assertEquals("ATTEMPT_FAILED",record().path("externalTimeline").get(1).path("type").asText());
  assertEquals(2,record().path("externalTimeline").size());
  failAudit=false;service.confirmExternal("o1",input,finance);service.confirmExternal("o1",input,finance);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM receipts",Integer.class));assertEquals(30,record().path("effectiveOrder").path("total").asInt());
  assertEquals(5,record().path("externalTimeline").size());
  assertEquals(original,db.state(false));assertEquals(20,record().path("snapshot").path("total").asInt());
  verify(client,never()).upload(any());verify(client,never()).mutate(anyString(),anyString(),any());
 }
 @Test void independentInstancesCannotDuplicateSettlementOrOverwriteAcceptedBaseline()throws Exception{
  var input=input();var second=new JushuitanService(db,client,m,manager,"sandbox");var gate=new CountDownLatch(1);
  try(var workers=Executors.newFixedThreadPool(2)){
   var jobs=List.of(service,second).stream().map(s->workers.submit(()->{gate.await();return s.confirmExternal("o1",input,finance);})).toList();gate.countDown();
   for(var job:jobs)assertFalse(job.get(15,TimeUnit.SECONDS).has("externalDifference"));
  }
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM receipts",Integer.class));
  assertEquals(4,record().path("externalTimeline").size());
  assertEquals(1,record().path("externalChanges").size());assertEquals(30,record().path("request").path("pay_amount").asInt());
  var synced=service.sync("o1");assertFalse(synced.has("externalDifference"));assertEquals("ACCEPTED",synced.path("status").asText());assertEquals(original,db.state(false));
 }
 @Test void crmOrderEditAndDetailedJournalCommitOrRollbackTogether(){
  original.put("revision",0);jdbc.update("UPDATE state SET payload=? WHERE id=1",db.encode(original));
  var actor=new Actor("editor","销售",Set.of("business.read","business.write"));
  var failing=new CrmRepository(jdbc,m){@Override public void audit(String user,String action,JsonNode details){throw new IllegalStateException("simulated detailed journal failure");}};
  var business=new BusinessService(failing,mock(AssetService.class));
  var next=original.deepCopy();((ObjectNode)next.path("orders").get(0)).put("note","新备注");
  var body=m.createObjectNode().put("revision",0);body.set("data",next);
  var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
  var failure=assertThrows(IllegalStateException.class,()->tx.execute(status->business.save(body,actor)));
  assertEquals("simulated detailed journal failure",failure.getMessage());
  assertEquals(original,db.state(false));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit",Integer.class));
  var valid=new BusinessService(db,mock(AssetService.class));
  tx.execute(status->valid.save(body,actor));
  var entries=(List<?>)valid.orderHistory("o1",actor).get("entries");assertEquals(1,entries.size());
  var entry=(JsonNode)entries.get(0);assertEquals("editor",entry.path("user").asText());
  assertEquals("新备注",entry.path("details").path("after").path("note").asText());
  assertFalse(entry.path("details").path("before").has("note"));
 }

 void auditFixture(String id,String time,String orderId){
  var entry=m.createObjectNode().put("id",id).put("time",time).put("user","editor").put("action","修改订单");
  entry.putObject("details").put("orderId",orderId).put("before","旧备注".repeat(100)).put("after","新备注".repeat(100));
  jdbc.update("INSERT INTO audit(id,payload) VALUES(?,?)",id,db.encode(entry));
 }
 @Test void completedQuartersCompressAndOrderHistoryStillIncludesEveryRevision(){
  auditFixture("old-q2","2026-04-01T00:00:00Z","o1");
  auditFixture("last-q3","2026-09-30T15:59:59.999Z","o1");
  auditFixture("current","2026-09-30T16:00:00.000Z","o1");
  auditFixture("other","2026-08-01T00:00:00Z","other");
  var before=db.documents("audit",false,0);var archive=new AuditArchiveService(db,manager);
  assertEquals(3,archive.archiveBatch(java.time.Instant.parse("2026-10-23T00:00:00Z")));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_archives",Integer.class));
  assertEquals(before,db.documents("audit",false,0));
  var business=new BusinessService(db,mock(AssetService.class));
  var entries=(List<?>)business.orderHistory("o1",new Actor("reader","销售",Set.of("business.read"))).get("entries");
  assertEquals(3,entries.size());assertEquals(before.get(0),entries.get(0));
  assertFalse(db.parse(jdbc.queryForObject("SELECT payload FROM audit WHERE id='current'",String.class)).has("_archive"));
  assertEquals(0,archive.archiveBatch(java.time.Instant.parse("2026-10-23T00:00:00Z")));
  assertEquals(before.reversed(),db.documents("audit",true,500));
 }
 @Test void quarterlyArchiveFailureRollsBackBothContentAndPointers(){
  auditFixture("old","2026-08-01T00:00:00Z","o1");var before=db.documents("audit",false,0);
  jdbc.execute("CREATE TRIGGER fail_audit_archive BEFORE UPDATE ON audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture pointer failure'");
  var archive=new AuditArchiveService(db,manager);var now=java.time.Instant.parse("2026-10-23T00:00:00Z");
  assertThrows(RuntimeException.class,()->archive.archiveBatch(now));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_archives",Integer.class));
  assertEquals(before,db.documents("audit",false,0));
  jdbc.execute("DROP TRIGGER fail_audit_archive");assertEquals(1,archive.archiveBatch(now));assertEquals(before,db.documents("audit",false,0));
  jdbc.update("UPDATE audit_archives SET sha256=?","0".repeat(64));
  assertThrows(IllegalStateException.class,()->db.documents("audit",false,0));
 }
 @Test void aQuarterCanSpanBatchesWithoutDroppingOrRepeatingRecords(){
  for(int i=0;i<130;i++)auditFixture("entry-"+i,"2026-08-01T00:00:00Z","o1");
  var before=db.documents("audit",false,0);var archive=new AuditArchiveService(db,manager);var now=java.time.Instant.parse("2026-10-23T00:00:00Z");
  assertEquals(64,archive.archiveBatch(now));assertEquals(64,archive.archiveBatch(now));assertEquals(2,archive.archiveBatch(now));assertEquals(0,archive.archiveBatch(now));
  assertEquals(130,jdbc.queryForObject("SELECT SUM(record_count) FROM audit_archives",Integer.class));
  assertEquals(before,db.documents("audit",false,0));
 }

 @Test void concurrentArchiversDoNotDuplicateOrLoseQuarterRecords()throws Exception{
  for(int i=0;i<70;i++)auditFixture("concurrent-"+i,"2026-08-01T00:00:00Z","o1");
  var before=db.documents("audit",false,0);var first=new AuditArchiveService(db,manager);var second=new AuditArchiveService(db,manager);
  var now=java.time.Instant.parse("2026-10-23T00:00:00Z");var start=new CountDownLatch(1);
  try(var workers=Executors.newFixedThreadPool(2)){
   var jobs=List.of(first,second).stream().map(svc->workers.submit(()->{start.await();return svc.archiveBatch(now);})).toList();start.countDown();
   for(var job:jobs)job.get(15,TimeUnit.SECONDS);
  }
  while(first.archiveBatch(now)>0){}
  assertEquals(70,jdbc.queryForObject("SELECT SUM(record_count) FROM audit_archives",Integer.class));
  assertEquals(before,db.documents("audit",false,0));
 }

}
