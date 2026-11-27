package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JushuitanOrderIdentityTest {
  private static final String NUMBER = "N2611260001";
  private static final String LEGACY = "CRM-0123456789abcdef0123456789abcdef";
  private final ObjectMapper mapper = new ObjectMapper();
  private final JushuitanClient client = mock(JushuitanClient.class);

  private ObjectNode order(String id) { return mapper.createObjectNode().put("id", id); }
  private ObjectNode request(String soId) { return mapper.createObjectNode().put("shop_id", 18830986).put("so_id", soId); }
  private ObjectNode page(boolean next) {
    var response = mapper.createObjectNode().put("code", 0);
    response.putObject("data").put("has_next", next).putArray("orders");
    return response;
  }
  private void fails(JsonNode request, int status) {
    assertEquals(status, assertThrows(ApiException.class,
        () -> JushuitanOrderIdentity.requireUnused(client, mapper, request)).status());
    verify(client, never()).upload(any());
    verify(client, never()).mutate(any(), any(), any());
  }

  @Test void newOrdersReuseOnlyTheirValidReservedBusinessNumberWithoutChangingTheSnapshot() {
    var ordinary = order(NUMBER); var before = ordinary.deepCopy();
    assertEquals(NUMBER, JushuitanOrderIdentity.create(ordinary));
    assertEquals(before, ordinary);
    var supplement = order("JS-internal-reference").put("orderNumber", NUMBER);
    assertEquals(NUMBER, JushuitanOrderIdentity.create(supplement));
    assertTrue(JushuitanOrderIdentity.isUnified(NUMBER, supplement));
    assertTrue(JushuitanOrderIdentity.valid(NUMBER, ordinary));
  }

  @Test void legacyAndUnrecognizedBusinessNumbersRetainTheExistingRandomIdentityNamespace() {
    var identities = new HashSet<String>();
    for (String id : List.of("2026-11-00014583", "S261125151751967", "JS-legacy", "N2613320001", "N2611260000")) {
      String identity = JushuitanOrderIdentity.create(order(id));
      assertTrue(identity.matches("CRM-[a-f0-9]{32}"));
      assertTrue(identities.add(identity));
      assertTrue(JushuitanOrderIdentity.valid(identity, order(id)));
    }
    assertTrue(JushuitanOrderIdentity.create(null).matches("CRM-[a-f0-9]{32}"));
  }

  @Test void validationAcceptsFrozenLegacyIdentitiesButNeverAnotherOrdersShortNumberOrLoosePrefixes() {
    assertTrue(JushuitanOrderIdentity.valid(LEGACY, order(NUMBER)));
    assertTrue(JushuitanOrderIdentity.valid(LEGACY, null));
    for (String identity : List.of("N2611260002", "N2613320001", "N2611260000", "N261126001", NUMBER + "-2",
        " " + NUMBER, NUMBER + " ", "CRM-short", "CRM-" + "A".repeat(32), "CRM-" + "a".repeat(33)))
      assertFalse(JushuitanOrderIdentity.valid(identity, order(NUMBER)), identity);
    assertFalse(JushuitanOrderIdentity.valid(null, order(NUMBER)));
    assertFalse(JushuitanOrderIdentity.valid(NUMBER, null));
    assertTrue(JushuitanOrderIdentity.isBusinessNumber("N26112610000"));
    assertFalse(JushuitanOrderIdentity.isBusinessNumber("N2611269223372036854775808"));
  }

  @Test void legacyPreflightDoesNotPerformAnyAdditionalReadOrWrite() {
    JushuitanOrderIdentity.requireUnused(client, mapper, request(LEGACY).removeAll().put("so_id", LEGACY));
    verifyNoInteractions(client);
  }

  @Test void firstUploadPreflightReadsOnlyTheSameShopAndExactNumberWithoutChangingTheRequest() {
    var input = request(NUMBER).put("remark", "业务备注"); var before = input.deepCopy();
    when(client.query(eq("/open/orders/single/query"), any())).thenReturn(page(false));
    JushuitanOrderIdentity.requireUnused(client, mapper, input);
    var queries = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(client).query(eq("/open/orders/single/query"), queries.capture());
    var query = queries.getValue();
    assertEquals(18830986, query.path("shop_id").asLong());
    assertEquals(NUMBER, query.path("so_ids").get(0).asText()); assertEquals(1, query.path("so_ids").size());
    assertEquals(1, query.path("page_index").asInt()); assertEquals(50, query.path("page_size").asInt());
    assertEquals(4, query.size()); // No date window can hide an older collision.
    assertEquals(before, input);
    verifyNoMoreInteractions(client);
  }

  @Test void absenceMustBeEstablishedAcrossEveryPage() {
    when(client.query(anyString(), any())).thenReturn(page(true), page(true), page(false));
    JushuitanOrderIdentity.requireUnused(client, mapper, request(NUMBER));
    var queries = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(client, times(3)).query(eq("/open/orders/single/query"), queries.capture());
    assertEquals(List.of(1, 2, 3), queries.getAllValues().stream().map(q -> q.path("page_index").asInt()).toList());
    assertTrue(queries.getAllValues().stream().allMatch(q -> q.path("shop_id").asLong() == 18830986
        && NUMBER.equals(q.path("so_ids").get(0).asText())));
    verifyNoMoreInteractions(client);
  }

  @Test void anyExistingExactIdentityIsAConflictIncludingForeignShopsAndIncompleteRecords() {
    for (Long shop : new Long[]{18830986L, 999L, null}) {
      reset(client);
      var response = page(false);
      var existing = response.withObject("data").withArray("orders").addObject().put("so_id", NUMBER);
      if (shop != null) existing.put("shop_id", shop).put("o_id", 571794);
      when(client.query(anyString(), any())).thenReturn(response);
      fails(request(NUMBER), 409);
      verify(client, times(1)).query(anyString(), any());
    }
  }

  @Test void aCollisionOnALaterPageStillStopsBeforeAnyUpload() {
    var collision = page(false);
    collision.withObject("data").withArray("orders").addObject().put("so_id", NUMBER).put("shop_id", 18830986);
    when(client.query(anyString(), any())).thenReturn(page(true), collision);
    fails(request(NUMBER), 409);
    verify(client, times(2)).query(anyString(), any());
  }

  @Test void malformedOrFilteredWrongIdentityResultsCannotEstablishAnUnusedNumber() {
    var malformed = mapper.createArrayNode().addNull();
    var missingCode = page(false); missingCode.remove("code");
    var failed = page(false).put("code", 1);
    var textualCode = page(false).put("code", "0");
    var absentOrders = page(false); absentOrders.withObject("data").remove("orders");
    var absentNext = page(false); absentNext.withObject("data").remove("has_next");
    var textualNext = page(false); textualNext.withObject("data").put("has_next", "false");
    var wrongIdentity = page(false); wrongIdentity.withObject("data").withArray("orders").addObject().put("so_id", "N2611260002");
    var malformedRow = page(false); malformedRow.withObject("data").withArray("orders").addObject().put("so_id", 1);
    for (JsonNode response : List.of(malformed, missingCode, failed, textualCode, absentOrders, absentNext, textualNext, wrongIdentity, malformedRow)) {
      reset(client); when(client.query(anyString(), any())).thenReturn(response);
      fails(request(NUMBER), 502);
    }
    reset(client); when(client.query(anyString(), any())).thenReturn(null);
    fails(request(NUMBER), 502);
  }

  @Test void anUnfinishedQueryFailsClosedAtThePaginationBound() {
    when(client.query(anyString(), any())).thenReturn(page(true));
    fails(request(NUMBER), 502);
    verify(client, times(20)).query(anyString(), any());
  }

  @Test void invalidNewRequestIdentityOrShopDoesNotReachTheUpstream() {
    for (var input : List.of(request("N2613320001"), request(NUMBER + "-extra"), request(NUMBER).put("shop_id", "18830986"),
        request(NUMBER).put("shop_id", 0), request(NUMBER).put("shop_id", 1.5), request(NUMBER).putNull("so_id")))
      fails(input, 400);
    fails(null, 400);
    verifyNoInteractions(client);
  }

  @Test void networkOrGatewayFailureCannotBeTreatedAsAnUnusedNumber() {
    when(client.query(anyString(), any())).thenThrow(new ApiException(503, "查询服务暂不可用"));
    fails(request(NUMBER), 503);
    reset(client); when(client.query(anyString(), any())).thenThrow(new IllegalStateException("private network details"));
    var failure = assertThrows(ApiException.class, () -> JushuitanOrderIdentity.requireUnused(client, mapper, request(NUMBER)));
    assertEquals(502, failure.status()); assertFalse(failure.getMessage().contains("private network details"));
    verify(client, never()).upload(any());
  }
}
