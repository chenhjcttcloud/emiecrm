package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
  @Test void activeImageFormatsAndOversizedBodiesAreRejected() {
    var service = service();
    assertThrows(ApiException.class, () -> service.store("<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8)));
    assertThrows(ApiException.class, () -> service.store(new byte[5 * 1024 * 1024 + 1]));
    verifyNoInteractions(jdbc);
  }
}
