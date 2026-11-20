package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessStateRefreshTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private CrmRepository db;
  private BusinessService service;
  private JsonNode document;

  @BeforeEach void setup() throws Exception {
    db = mock(CrmRepository.class);
    when(db.object()).thenAnswer(call -> mapper.createObjectNode());
    document = mapper.readTree("""
        {"revision":12,"customers":[{"id":"c1","name":"客户甲","legacySource":{"preserved":"raw source"},"openingBalance":80}],
         "products":[{"id":"p1"}],"orders":[{"id":"o1","customer":"客户甲"}]}
        """);
    when(db.state(false)).thenAnswer(call -> document.deepCopy());
    service = new BusinessService(db, mock(AssetService.class));
  }

  @Test void unchangedRevisionDoesNotReadOrParseTheBusinessDocument() {
    when(db.rows(anyString())).thenReturn(List.of(Map.of("revision", "12")));
    JsonNode result = service.state(12L);
    assertEquals(2, result.size());
    assertTrue(result.path("unchanged").asBoolean());
    assertEquals(12L, result.path("revision").asLong());
    verify(db, never()).state(anyBoolean());
    verify(db, never()).parse(anyString());
    verify(db, never()).state(any(JsonNode.class));
  }

  @Test void changedOrMissingKnownRevisionPreservesCompleteDataAndOrderNormalization() {
    when(db.rows(anyString())).thenReturn(List.of(Map.of("revision", "12")));
    for (Long revision : new Long[]{11L, 13L, null}) {
      JsonNode result = service.state(revision);
      assertEquals(document.path("customers"), result.path("customers"));
      assertEquals(document.path("products"), result.path("products"));
      assertEquals("c1", result.path("orders").get(0).path("customerId").asText());
      assertFalse(result.has("unchanged"));
    }
    verify(db, times(3)).state(false);
  }

  @Test void uninitializedOrUnversionedStateIsNeverMistakenForUnchanged() {
    when(db.rows(anyString())).thenReturn(List.of());
    when(db.state(false)).thenReturn(NullNode.instance);
    assertTrue(service.state(0L).isNull());
    when(db.rows(anyString())).thenReturn(List.of(Map.of("revision", "null")));
    when(db.state(false)).thenReturn(document);
    assertFalse(service.state(0L).has("unchanged"));
  }

  @Test void negativeKnownRevisionIsRejectedBeforeReadingDatabase() {
    assertThrows(ApiException.class, () -> service.state(-1L));
    verify(db, never()).rows(anyString());
    verify(db, never()).state(anyBoolean());
  }
}
