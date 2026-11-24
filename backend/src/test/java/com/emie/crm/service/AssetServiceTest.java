package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final CrmRepository db = mock(CrmRepository.class);
  private AssetService service() { when(db.jdbc()).thenReturn(jdbc); return new AssetService(db); }

  @Test void duplicateImageReferencesUseOneMetadataQuery() throws Exception {
    var service = service();
    String id = "a".repeat(64), url = AssetService.PREFIX + id;
    var state = new ObjectMapper().readTree("{\"products\":[{\"image\":\"" + url + "\"},{\"image\":\"" + url
        + "\"}],\"orders\":[{\"attachments\":[{\"url\":\"" + url + "\"}]}]}");
    when(jdbc.queryForObject("SELECT COUNT(*) FROM assets WHERE id IN (?)", Long.class, id)).thenReturn(1L);
    service.externalize(state);
    verify(jdbc).queryForObject("SELECT COUNT(*) FROM assets WHERE id IN (?)", Long.class, id);
    verifyNoMoreInteractions(jdbc);
  }
  @Test void missingImageReferenceIsRejected() throws Exception {
    var service = service(); String id = "b".repeat(64);
    when(jdbc.queryForObject("SELECT COUNT(*) FROM assets WHERE id IN (?)", Long.class, id)).thenReturn(0L);
    var state = new ObjectMapper().readTree("{\"products\":[{\"image\":\"" + AssetService.PREFIX + id + "\"}],\"orders\":[]}");
    assertThrows(ApiException.class, () -> service.externalize(state));
  }
  @Test void nullableLegacyImagesDoNotBreakOrdinarySaves() throws Exception {
    var service = service();
    service.externalize(new ObjectMapper().readTree("{\"products\":[{\"image\":null},{}],\"orders\":[]}"));
    verifyNoInteractions(jdbc);
  }
  @Test void vendorPictureDoesNotBreakBusinessSaveOrLeakIntoAssetStorage() throws Exception {
    var service=service();
    service.externalize(new ObjectMapper().readTree("{\"products\":[{\"source\":\"jushuitan\",\"jstImage\":\"https://cdn.example.com/a.png\"}],\"orders\":[]}"));
    verifyNoInteractions(jdbc);
  }
  @Test void activeImageFormatsAndOversizedBodiesAreRejected() {
    var service = service();
    assertThrows(ApiException.class, () -> service.store("<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8)));
    assertThrows(ApiException.class, () -> service.store(new byte[5 * 1024 * 1024 + 1]));
    verifyNoInteractions(jdbc);
  }
  @Test void backupIncludesReceiptAndApprovalHistoryImagesAfterTheirOrderAttachmentsAreRemoved() throws Exception {
    var service=service(); var mapper=new ObjectMapper();
    var state=mapper.readTree("{\"products\":[],\"orders\":[{\"attachments\":[]}]}");
    String receipt="a".repeat(64), frozenOrder="b".repeat(64), oldRound="c".repeat(64);
    byte[] bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=");
    for(String id:List.of(receipt,frozenOrder,oldRound))when(jdbc.queryForList("SELECT content_type,content FROM assets WHERE id=?",id))
        .thenReturn(List.of(Map.of("content_type","image/png","content",bytes)));
    var ledger=mapper.createObjectNode();ledger.putArray("attachments").addObject().put("name","本次回单.png").put("url",AssetService.PREFIX+receipt);
    var approval=mapper.createObjectNode();approval.putObject("orderSnapshot").putArray("attachments")
        .addObject().put("name","原预收款.png").put("url",AssetService.PREFIX+frozenOrder);
    approval.putArray("history").addObject().putObject("orderSnapshot").putArray("attachments")
        .addObject().put("name","前次申请.png").put("url",AssetService.PREFIX+oldRound);
    var batch=mapper.createObjectNode();batch.set("attachments",ledger.path("attachments").deepCopy());
    var original=approval.deepCopy();
    var result=service.backup(state,List.of(ledger),List.of(approval),List.of(batch));
    assertEquals(java.util.Set.of(receipt,frozenOrder,oldRound),result.keySet());
    assertEquals(Base64.getEncoder().encodeToString(bytes),mapper.valueToTree(result).path(receipt).path("data").asText());
    assertEquals(original,approval);
    verify(jdbc,times(1)).queryForList("SELECT content_type,content FROM assets WHERE id=?",receipt);
  }
  @Test void immutableEvidenceReferencesAreValidatedWithoutMutatingFrozenDocuments() throws Exception {
    var service=service();var mapper=new ObjectMapper();String id="d".repeat(64);
    var approval=mapper.createObjectNode();approval.putArray("history").addObject().putObject("orderSnapshot").putArray("attachments")
        .addObject().put("name","已归档.png").put("url",AssetService.PREFIX+id);
    var before=approval.deepCopy();
    when(jdbc.queryForObject("SELECT COUNT(*) FROM assets WHERE id IN (?)",Long.class,id)).thenReturn(1L);
    service.validateEvidence(approval);
    assertEquals(before,approval);
    when(jdbc.queryForObject("SELECT COUNT(*) FROM assets WHERE id IN (?)",Long.class,id)).thenReturn(0L);
    assertThrows(ApiException.class,()->service.validateEvidence(approval));
    assertEquals(before,approval);verify(jdbc,never()).update(anyString(),any(Object[].class));
  }
}
