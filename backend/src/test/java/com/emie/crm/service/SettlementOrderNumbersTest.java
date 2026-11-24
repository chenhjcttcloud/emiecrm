package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class SettlementOrderNumbersTest {
  private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
  private final YearMonth fallback = YearMonth.of(2026, 11);

  @Test void backfillUsesCreationOrderShanghaiMonthAndTheOrdinaryOrderSequence() throws Exception {
    var state = state("""
        [{"id":"latest","source":"jushuitan-settlement","created":"2026-12-01T00:00:00Z","status":"审批中"},
         {"id":"nov-second","source":"jushuitan-settlement","created":"2026-11-02 10:00:00","settlement":{"cycle":"month"}},
         {"id":"2026-11-00000006","type":"销售订单","created":"2026-11-01","status":"草稿"},
         {"id":"dec-first","source":"jushuitan-settlement","created":"2026-11-30T16:00:00Z","status":"已批准"},
         {"id":"nov-first","source":"jushuitan-settlement","created":"2026-11-01 09:00:00","settlement":{"cycle":"day"}}]
        """);
    assertEquals(4, SettlementOrderNumbers.assignMissing(state, fallback));
    assertEquals("2026-11-00000007", order(state,"nov-first").path("orderNumber").asText());
    assertEquals("2026-11-00000008", order(state,"nov-second").path("orderNumber").asText());
    assertEquals("2026-12-00000009", order(state,"dec-first").path("orderNumber").asText());
    assertEquals("2026-12-00000010", order(state,"latest").path("orderNumber").asText());
    assertFalse(order(state,"2026-11-00000006").has("orderNumber"));
    assertEquals("已批准", order(state,"dec-first").path("status").asText());
    var numbered = state.deepCopy();
    assertEquals(0, SettlementOrderNumbers.assignMissing(state, YearMonth.of(2027,1)));
    assertEquals(numbered, state);
  }

  @Test void retainedCanceledOrDeletedNumbersAreNeverReusedAndExistingNumbersNeverMove() throws Exception {
    var state = state("""
        [{"id":"old","source":"jushuitan-settlement","orderNumber":"2026-11-00000009","status":"已取消","deletedAt":"2026-11-02"},
         {"id":"backdated","source":"jushuitan-settlement","created":"2026-11-01"},
         {"id":"new","source":"jushuitan-settlement","created":"2026-11-03"}]
        """);
    SettlementOrderNumbers.assignMissing(state,fallback);
    assertEquals("2026-11-00000009",order(state,"old").path("orderNumber").asText());
    assertEquals("2026-11-00000010",order(state,"backdated").path("orderNumber").asText());
    assertEquals("2026-11-00000011",order(state,"new").path("orderNumber").asText());
  }

  @Test void legacyMissingCreationDateUsesTheBackfillMonthAndStableIdOrder() throws Exception {
    var state = state("""
        [{"id":"b","source":"jushuitan-settlement","created":"invalid"},
         {"id":"a","source":"jushuitan-settlement"}]
        """);
    SettlementOrderNumbers.assignMissing(state,fallback);
    assertEquals("2026-11-00000001",order(state,"a").path("orderNumber").asText());
    assertEquals("2026-11-00000002",order(state,"b").path("orderNumber").asText());
  }

  @Test void duplicatesAndMalformedNumbersCannotReplaceTheSharedSequence() throws Exception {
    for (String number : new String[]{"2026-11-00000000","2026-13-00000001","2026-11-1","arbitrary"}) {
      var state=state("[]");state.withArray("orders").addObject().put("id","a").put("source","jushuitan-settlement").put("orderNumber",number);
      assertThrows(ApiException.class,()->SettlementOrderNumbers.assignMissing(state,fallback));
    }
    var duplicate=state("[]");
    for(String id:new String[]{"a","b"}) duplicate.withArray("orders").addObject().put("id",id).put("source","jushuitan-settlement").put("orderNumber","2026-11-00000001");
    assertThrows(ApiException.class,()->SettlementOrderNumbers.assignMissing(duplicate,fallback));
  }

  @Test void deprecatedSeparateNumbersJoinTheSharedSequenceWithoutChangingInternalIds() throws Exception {
    var state=state("""
        [{"id":"2026-10-00000006","type":"销售订单"},
         {"id":"JS-internal-uuid","source":"jushuitan-settlement","orderNumber":"JS-2026-11-00000099","created":"2026-11-01"}]
        """);
    assertEquals(1,SettlementOrderNumbers.assignMissing(state,fallback));
    assertEquals("2026-11-00000007",order(state,"JS-internal-uuid").path("orderNumber").asText());
    assertEquals(0,SettlementOrderNumbers.assignMissing(state,fallback));
  }

  @Test void ordinaryIdsAndSupplementNumbersCannotCollideAndLongSequencesKeepTheirPrecision() throws Exception {
    var state=state("""
        [{"id":"2026-11-00000007","type":"销售订单"},
         {"id":"JS-internal","source":"jushuitan-settlement","orderNumber":"2026-11-00000007"}]
        """);
    assertEquals(409,assertThrows(ApiException.class,()->SettlementOrderNumbers.assignMissing(state,fallback)).status());
    var longSequence=state("""
        [{"id":"2026-10-9007199254740993","type":"销售订单"},
         {"id":"JS-new","source":"jushuitan-settlement","created":"2026-11-01"}]
        """);
    SettlementOrderNumbers.assignMissing(longSequence,fallback);
    assertEquals("2026-11-9007199254740994",order(longSequence,"JS-new").path("orderNumber").asText());
  }

  @Test void invalidHistoricalInternalNumbersAreNotAdoptedAndTheMigrationRemainsIdempotent() throws Exception {
    var state=state("""
        [{"id":"2026-13-00000001","source":"jushuitan-settlement","created":"2026-11-01"},
         {"id":"2026-11-00000000","source":"jushuitan-settlement","created":"2026-11-01"}]
        """);
    assertEquals(2,SettlementOrderNumbers.assignMissing(state,fallback));
    for(var order:state.path("orders")) assertTrue(order.path("orderNumber").asText().matches("2026-11-0000000[23]"));
    var before=state.deepCopy();
    assertEquals(0,SettlementOrderNumbers.assignMissing(state,fallback));
    assertEquals(before,state);
  }

  @Test void clientsCannotInjectChangeOrEraseANumberAndAnOldBackupKeepsItsAssignedNumber() throws Exception {
    var old=state("[]");
    old.withArray("orders").addObject().put("id","a").put("source","jushuitan-settlement").put("orderNumber","2026-11-00000001");
    for(String value:new String[]{"","2026-11-00000002"}) {
      var next=old.deepCopy();order(next,"a").put("orderNumber",value);
      assertThrows(ApiException.class,()->SettlementOrderNumbers.protect(old,next,false));
      assertThrows(ApiException.class,()->SettlementOrderNumbers.protect(old,next,true));
    }
    var next=old.deepCopy();order(next,"a").remove("orderNumber");
    assertThrows(ApiException.class,()->SettlementOrderNumbers.protect(old,next,false));
    SettlementOrderNumbers.protect(old,next,true);
    assertEquals(old,next);
    var empty=state("[]");
    assertThrows(ApiException.class,()->SettlementOrderNumbers.protect(empty,old,false));
  }

  private ObjectNode state(String orders) throws Exception { return (ObjectNode)mapper.readTree("{\"orders\":"+orders+"}"); }
  private ObjectNode order(ObjectNode state,String id) {
    for(var order:state.path("orders")) if(id.equals(order.path("id").asText())) return (ObjectNode)order;
    throw new AssertionError("Missing order "+id);
  }
}
