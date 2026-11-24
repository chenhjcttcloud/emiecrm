package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceiptEvidenceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AssetService assets = mock(AssetService.class);
  private final String id = "a".repeat(64);
  private final byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=");

  private ArrayNode evidence() {
    when(assets.read(id)).thenReturn(new AssetService.Asset("image/png", image));
    var result = mapper.createArrayNode();
    result.addObject().put("name", "银行回单.png").put("url", AssetService.PREFIX + id);
    return result;
  }

  @Test void acceptsOnlyUploadedImagesAndReturnsAnIndependentSnapshot() {
    var input = evidence();
    var frozen = ReceiptEvidence.validate(assets, input);
    assertEquals(input, frozen);
    input.removeAll();
    assertEquals(1, frozen.size());
    assertEquals("银行回单.png", frozen.get(0).path("name").asText());
    verify(assets).read(id);
  }

  @Test void absentLegacyEvidenceIsOptionalAndDoesNotReadAssets() {
    assertTrue(ReceiptEvidence.validate(assets, MissingNode.getInstance()).isEmpty());
    assertTrue(ReceiptEvidence.validate(assets, NullNode.instance).isEmpty());
    verifyNoInteractions(assets);
  }

  @Test void externalUrlsInlineDataAndMalformedAssetIdsCannotBypassUpload() {
    for (String url : new String[]{"https://example.com/proof.png", "data:image/png;base64,aW1hZ2U=",
        "/api/assets/" + "a".repeat(63), "/api/assets/" + "A".repeat(64), AssetService.PREFIX + id + "?download=1"}) {
      var value = mapper.createArrayNode(); value.addObject().put("name", "凭证.png").put("url", url);
      assertThrows(ApiException.class, () -> ReceiptEvidence.validate(assets, value));
    }
    verifyNoInteractions(assets);
  }

  @Test void rejectsMissingAssetsAndOversizedOrNonimageStoredContent() {
    var value = evidence();
    when(assets.read(id)).thenThrow(new ApiException(404, "图片不存在"));
    assertThrows(ApiException.class, () -> ReceiptEvidence.validate(assets, value));
    doReturn(new AssetService.Asset("application/pdf", image)).when(assets).read(id);
    assertThrows(ApiException.class, () -> ReceiptEvidence.validate(assets, value));
    doReturn(new AssetService.Asset("image/png", new byte[5 * 1024 * 1024 + 1])).when(assets).read(id);
    assertThrows(ApiException.class, () -> ReceiptEvidence.validate(assets, value));
  }

  @Test void rejectsMoreThanTenImagesAndMalformedNamesBeforeReadingAssets() {
    var value = mapper.createArrayNode();
    for (int i = 0; i < 11; i++) value.addObject().put("name", "凭证.png").put("url", AssetService.PREFIX + id);
    assertThrows(ApiException.class, () -> ReceiptEvidence.validate(assets, value));
    for (String name : new String[]{"", "字".repeat(256), "回单\n.png"}) {
      var invalid = mapper.createArrayNode(); invalid.addObject().put("name", name).put("url", AssetService.PREFIX + id);
      assertThrows(ApiException.class, () -> ReceiptEvidence.validate(assets, invalid));
    }
    var nontext = mapper.createArrayNode(); nontext.addObject().put("name", 3).put("url", AssetService.PREFIX + id);
    assertThrows(ApiException.class, () -> ReceiptEvidence.validate(assets, nontext));
    verifyNoInteractions(assets);
  }
}
