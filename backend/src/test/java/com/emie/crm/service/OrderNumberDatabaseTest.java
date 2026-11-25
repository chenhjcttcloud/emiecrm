package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** All numbering mutations target the explicitly named disposable order-approval fixture only. */
@EnabledIfEnvironmentVariable(named="CRM_ORDER_APPROVAL_DB_TEST",matches="1")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OrderNumberDatabaseTest {
  private static final String DATABASE="emiecrm_order_approval_test";
  private static final String URL="jdbc:mysql://emiecrm-order-approval-fixture:3306/"+DATABASE
      +"?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=15000";
  private final ObjectMapper mapper=new ObjectMapper();
  private final Actor alice=new Actor("alice","销售",Set.of("business.read","business.write"));
  private final Actor bob=new Actor("bob","销售",Set.of("business.read","business.write"));
  private JdbcTemplate jdbc;
  private CrmRepository db;
  private DataSourceTransactionManager manager;

  @BeforeEach void fixture() throws Exception {
    var source=new DriverManagerDataSource(URL,"root","fixture-only");jdbc=new JdbcTemplate(source);
    assertEquals(DATABASE,jdbc.queryForObject("SELECT DATABASE()",String.class));
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
    for(String table:jdbc.queryForList("SHOW TABLES",String.class)) {
      assertTrue(table.matches("[a-z][a-z0-9_]*"));jdbc.update("DELETE FROM `"+table+"`");
    }
    manager=new DataSourceTransactionManager(source);db=new CrmRepository(jdbc,mapper);
    jdbc.update("INSERT INTO state(id,payload) VALUES(1,?)",db.encode(empty()));
  }

  private ObjectNode empty() {var state=mapper.createObjectNode().put("revision",1);state.putArray("orders");return state;}
  private OrderNumberService at(String instant) {return new OrderNumberService(db,manager,Clock.fixed(Instant.parse(instant),ZoneOffset.UTC));}
  private TransactionTemplate tx() {return new TransactionTemplate(manager);}
  private String reserve(OrderNumberService service,String key,Actor actor) {return service.reserve(key,actor).path("orderNumber").asText();}
  private String key(String suffix) {return "number-request-"+suffix;}
  private ObjectNode ordinary(String number) {var state=empty();state.withArray("orders").addObject().put("id",number).put("type","销售订单");return state;}
  private void claim(ObjectNode next,Actor actor) {tx().executeWithoutResult(t->{var old=db.state(true);OrderNumberService.requireNewNumbers(db,old,next,actor);db.state(next);});}

  private <T> List<T> concurrent(Callable<T> first,Callable<T> second) throws Exception {
    var ready=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
    try {
      var left=workers.submit(()->{assertTrue(ready.await(10,TimeUnit.SECONDS));return first.call();});
      var right=workers.submit(()->{assertTrue(ready.await(10,TimeUnit.SECONDS));return second.call();});
      ready.countDown();return List.of(left.get(20,TimeUnit.SECONDS),right.get(20,TimeUnit.SECONDS));
    } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
  }

  @Test void duplicateRequestsRemainIdempotentAcrossInstancesActorsAndShanghaiMidnight() throws Exception {
    var first=at("2026-11-24T15:59:59Z");var second=at("2026-11-24T15:59:59Z");
    var numbers=concurrent(()->reserve(first,key("same-request"),alice),()->reserve(second,key("same-request"),alice));
    assertEquals(List.of("N2611240001","N2611240001"),numbers);
    assertEquals("N2611240001",reserve(at("2026-11-24T16:00:01Z"),key("same-request"),alice));
    assertEquals("N2611250001",reserve(at("2026-11-24T16:00:01Z"),key("same-request"),bob));
    assertEquals("N2611250002",reserve(at("2026-11-24T16:00:01Z"),key("different-request"),alice));
    assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM order_number_reservations",Integer.class));
    assertEquals(1,db.state(false).path("revision").asInt());assertTrue(db.state(false).path("orders").isEmpty());
  }

  @Test void distinctConcurrentRequestsCannotShareANumber() throws Exception {
    var clock=at("2026-11-25T04:00:00Z");
    var numbers=concurrent(()->reserve(clock,key("alice-first"),alice),()->reserve(clock,key("bob-second"),bob));
    assertEquals(Set.of("N2611250001","N2611250002"),Set.copyOf(numbers));
    assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM order_number_reservations",Integer.class));
  }

  @Test void ordinaryAndSettlementReservationsShareTheDailySequenceButKeepTheirOwnIdentityScope() {
    var service=new OrderNumberService(db,manager);String prefix="N"+LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyMMdd"));
    assertEquals(prefix+"0001",reserve(service,key("shared-scope"),alice));
    String settlementId="JS-"+key("shared-scope");
    String settlement=tx().execute(t->OrderNumberService.reserveForSettlement(db,db.state(true),key("shared-scope"),settlementId,alice));
    assertEquals(prefix+"0002",settlement);
    assertEquals(settlement,tx().execute(t->OrderNumberService.reserveForSettlement(db,db.state(true),key("shared-scope"),settlementId,alice)));
    assertEquals(prefix+"0003",reserve(service,key("third-request"),bob));
    assertTrue(jdbc.queryForObject("SELECT consumed FROM order_number_reservations WHERE order_number=?",Boolean.class,settlement));
    assertEquals(409,assertThrows(ApiException.class,()->claim(ordinary(settlement),alice)).status());
  }

  @Test void firstSaveConsumesTheOwnedReservationAndCannotReuseItAfterTheOrderDisappears() {
    var service=at("2026-11-25T04:00:00Z");String number=reserve(service,key("owned-first"),alice);
    assertFalse(jdbc.queryForObject("SELECT consumed FROM order_number_reservations WHERE order_number=?",Boolean.class,number));
    assertEquals(409,assertThrows(ApiException.class,()->claim(ordinary(number),bob)).status());
    claim(ordinary(number),alice);
    assertTrue(jdbc.queryForObject("SELECT consumed FROM order_number_reservations WHERE order_number=?",Boolean.class,number));
    claim(ordinary(number),bob); // Existing records remain editable without claiming another actor's number.
    jdbc.update("UPDATE state SET payload=? WHERE id=1",db.encode(empty()));
    assertEquals(409,assertThrows(ApiException.class,()->claim(ordinary(number),alice)).status());
    assertEquals(number,reserve(service,key("owned-first"),alice));
    assertTrue(db.state(false).path("orders").isEmpty());
  }

  @Test void failedOrderPersistenceRollsBackTheClaimButDoesNotRecycleItsCommittedReservation() {
    String number=reserve(at("2026-11-25T04:00:00Z"),key("rollback-claim"),alice);
    assertThrows(ApiException.class,()->tx().executeWithoutResult(t->{
      OrderNumberService.requireNewNumbers(db,db.state(true),ordinary(number),alice);throw new ApiException("isolated persistence failure");
    }));
    assertFalse(jdbc.queryForObject("SELECT consumed FROM order_number_reservations WHERE order_number=?",Boolean.class,number));
    claim(ordinary(number),alice);assertEquals(number,db.state(false).at("/orders/0/id").asText());
  }

  @Test void restoredStateNumbersIncludingCanceledAndDeletedRecordsReserveTheWholeNamespaceAndExtendPast9999() {
    var restored=empty();var orders=restored.withArray("orders");
    orders.addObject().put("id","N2611259998").put("status","已取消").put("deletedAt","2026-11-25T01:00:00Z");
    orders.addObject().put("id","JS-restored-legacy").put("orderNumber","N2611259999");
    tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,restored);db.state(restored);});
    assertEquals("N26112510000",reserve(at("2026-11-25T04:00:00Z"),key("after-json-restore"),alice));
    assertEquals("N26112510001",reserve(at("2026-11-25T04:00:00Z"),key("after-expanded-number"),bob));
  }

  @Test void restoreCannotRebindReservedNumbersAndMarksARestoredMatchingOrderConsumed() {
    String number=reserve(at("2026-11-25T04:00:00Z"),key("restored-binding"),alice);
    var wrong=empty();wrong.withArray("orders").addObject().put("id","different-order").put("orderNumber",number);
    assertEquals(409,assertThrows(ApiException.class,()->tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,wrong);})).status());
    var matching=ordinary(number);
    tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,matching);db.state(matching);});
    assertTrue(jdbc.queryForObject("SELECT consumed FROM order_number_reservations WHERE order_number=?",Boolean.class,number));
    assertEquals(number,db.state(false).at("/orders/0/id").asText());
  }

  @Test void restoringANewDatabasePermanentlyReservesNumbersEvenAfterAnOlderEmptyBackupIsRestored() {
    var restored=ordinary("N2611250042");
    tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,restored);db.state(restored);});
    assertEquals("RESTORED",jdbc.queryForObject("SELECT scope FROM order_number_reservations WHERE order_number='N2611250042'",String.class));
    assertTrue(jdbc.queryForObject("SELECT consumed FROM order_number_reservations WHERE order_number='N2611250042'",Boolean.class));
    tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,empty());db.state(empty());});
    assertEquals("N2611250043",reserve(at("2026-11-25T04:00:00Z"),key("after-second-restore"),alice));
    var different=empty();different.withArray("orders").addObject().put("id","JS-different-restored").put("orderNumber","N2611250042");
    assertEquals(409,assertThrows(ApiException.class,()->tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,different);})).status());
    assertEquals("N2611250042",jdbc.queryForObject("SELECT order_id FROM order_number_reservations WHERE order_number='N2611250042'",String.class));
  }

  @Test void restoredAliasesAndLeadingZeroSequenceConflictsAreExplicitlyRejected() {
    var aliases=ordinary("N2611250001");((ObjectNode)aliases.at("/orders/0")).put("orderNumber","N2611250002");
    assertEquals(409,assertThrows(ApiException.class,()->tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,aliases);})).status());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_number_reservations",Integer.class));
    var restored=empty();restored.withArray("orders").addObject().put("id","JS-original-record").put("orderNumber","N2611250001");
    tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,restored);db.state(restored);});
    var alternate=empty();alternate.withArray("orders").addObject().put("id","JS-another-record").put("orderNumber","N26112500001");
    assertEquals(409,assertThrows(ApiException.class,()->tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,alternate);})).status());
    var rebound=empty();rebound.withArray("orders").addObject().put("id","JS-original-record").put("orderNumber","N2611250002");
    assertEquals(409,assertThrows(ApiException.class,()->tx().executeWithoutResult(t->{db.state(true);OrderNumberService.requireRestoredNumbers(db,rebound);})).status());
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_number_reservations",Integer.class));
  }

  @Test void malformedAndUnreservedNewNumbersFailButUnchangedLegacyIdsRemainCompatible() {
    assertEquals(409,assertThrows(ApiException.class,()->claim(ordinary("N2611250001"),alice)).status());
    assertThrows(ApiException.class,()->claim(ordinary("N2602290001"),alice));
    var legacy=ordinary("S261125151751967");claim(legacy,alice);
    assertEquals("S261125151751967",db.state(false).at("/orders/0/id").asText());
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_number_reservations",Integer.class));
  }

  @Test void sequenceOverflowFailsBeforeAnyReservationIsInserted() {
    var state=ordinary("N261125"+Long.MAX_VALUE);jdbc.update("UPDATE state SET payload=? WHERE id=1",db.encode(state));
    assertThrows(ApiException.class,()->reserve(at("2026-11-25T04:00:00Z"),key("overflow-request"),alice));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_number_reservations",Integer.class));
    assertEquals(state,db.state(false));
  }
}
