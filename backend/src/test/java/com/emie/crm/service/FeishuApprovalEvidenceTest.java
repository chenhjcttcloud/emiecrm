package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FeishuApprovalEvidenceTest {
  private static final String FIRST_ID = "a".repeat(64);
  private static final String SECOND_ID = "b".repeat(64);
  private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10,0};
  private static final byte[] JPEG = {(byte)255,(byte)216,(byte)255,0};
  private final ObjectMapper mapper = new ObjectMapper();
  private final AssetService assets = mock(AssetService.class);
  private final FeishuApprovalClient client = mock(FeishuApprovalClient.class);

  @BeforeEach void images() {
    when(assets.read(FIRST_ID)).thenReturn(new AssetService.Asset("image/png", PNG));
    when(assets.read(SECOND_ID)).thenReturn(new AssetService.Asset("image/jpeg", JPEG));
  }

  private ObjectNode definition(String type, boolean required) {
    var definition = mapper.createObjectNode();
    definition.putArray("form").addObject().put("id", "voucher").put("name", "收款凭证")
        .put("type", type).put("required", required);
    return definition;
  }

  private ArrayNode attachments() {
    var images = mapper.createArrayNode();
    images.addObject().put("name", "原始凭证.png").put("url", AssetService.PREFIX + FIRST_ID);
    images.addObject().put("name", "另一张原始凭证.jpg").put("url", AssetService.PREFIX + SECOND_ID);
    return images;
  }

  private ObjectNode record(String type, boolean orderSnapshot) {
    var images = attachments();
    var definition = definition(type, false);
    var record = mapper.createObjectNode().put("uuid", "frozen-approval-uuid").put("status", "SUBMITTING");
    if (orderSnapshot) record.putObject("orderSnapshot").set("attachments", images.deepCopy());
    else record.set("attachments", images.deepCopy());
    var form = record.putArray("form");
    form.addObject().put("id", "amount").put("type", "amount").put("value", 300);
    FeishuApprovalEvidence.append(mapper, definition, images, form);
    record.set(FeishuApprovalEvidence.FIELD, FeishuApprovalEvidence.plan(mapper, definition, images));
    return record;
  }

  private ObjectNode plan(ObjectNode record) { return (ObjectNode) record.path(FeishuApprovalEvidence.FIELD); }

  private ObjectNode voucher(ObjectNode record) {
    for (var value : record.path("form")) if ("voucher".equals(value.path("id").asText())) return (ObjectNode) value;
    throw new AssertionError("Missing voucher field");
  }

  private void uploads(String type) {
    String nativeType = "attachmentV2".equals(type) ? "attachment" : "image";
    when(client.uploadFile("receipt-voucher-1.png", nativeType, "image/png", PNG)).thenReturn("first-file-code");
    when(client.uploadFile("receipt-voucher-2.jpg", nativeType, "image/jpeg", JPEG)).thenReturn("second-file-code");
  }

  @ParameterizedTest @CsvSource({"附件,attachmentV2", "收款凭证,image", "付款凭证,imageV2"})
  void selectsTheSupportedVoucherWidgetAndCopiesFrozenMetadata(String name, String type) throws Exception {
    var definition = definition(type, true);
    ((ObjectNode) definition.path("form").get(0)).put("name", name);
    definition.withArray("form").addObject().put("id", "unrelated").put("name", "其他图片").put("type", "image");
    definition.put("form", mapper.writeValueAsString(definition.path("form")));
    var originals = attachments();
    var originalMetadata = originals.deepCopy();
    var plan = FeishuApprovalEvidence.plan(mapper, definition, originals);
    assertEquals("voucher", plan.path("widgetId").asText());
    assertEquals(type, plan.path("widgetType").asText());
    assertEquals(originalMetadata, plan.path("assets"));
    assertTrue(plan.path("codes").isArray() && plan.path("codes").isEmpty());
    assertFalse(plan.path("ready").asBoolean());
    assertFalse(plan.path("createAttempted").asBoolean());
    ((ObjectNode) originals.get(0)).put("name", "后来改名.png").put("url", "https://untrusted.example/image");
    assertEquals(originalMetadata, plan.path("assets"));
    verifyNoInteractions(assets, client);
  }

  @Test void requiredVouchersCannotBeEmptyAndOptionalAbsenceDoesNotInventAFile() {
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition("attachmentV2", true), mapper.createArrayNode()));
    assertNull(FeishuApprovalEvidence.plan(mapper, definition("attachmentV2", false), mapper.createArrayNode()));
    var noWidget = mapper.createObjectNode().putArray("form");
    var definition = mapper.createObjectNode().set("form", noWidget);
    assertNull(FeishuApprovalEvidence.plan(mapper, definition, mapper.createArrayNode()));
    assertTrue(assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition, attachments()))
        .getMessage().contains("尚无"));
    verifyNoInteractions(assets, client);
  }

  @Test void ambiguousOrDuplicateWidgetIdsCannotSilentlyChooseADifferentField() {
    var definition = definition("attachmentV2", false);
    definition.withArray("form").addObject().put("id", "second-voucher").put("name", "附件").put("type", "imageV2");
    assertTrue(assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition, attachments()))
        .getMessage().contains("多个"));
    definition.withArray("form").remove(1);
    definition.withArray("form").addObject().put("id", "voucher").put("name", "客户").put("type", "input");
    assertTrue(assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition, attachments()))
        .getMessage().contains("重复"));
    definition.withArray("form").remove(1);
    ((ObjectNode) definition.path("form").get(0)).put("id", "invalid/id");
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition, attachments()));
  }

  @Test void malformedDefinitionsAndOversizedOrNonArrayMetadataFailBeforeUploads() {
    for (String form : List.of("null", "{}", "not json")) {
      var definition = mapper.createObjectNode().put("form", form);
      assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition, attachments()));
    }
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition("image", false), mapper.createObjectNode()));
    var many = mapper.createArrayNode();
    for (int index = 0; index < 11; index++) many.add(attachments().get(0));
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.plan(mapper, definition("image", false), many));
    verifyNoInteractions(assets, client);
  }

  @ParameterizedTest @ValueSource(booleans={false, true})
  void resumesOnlyMissingImagesAfterAPersistedUploadCheckpoint(boolean orderSnapshot) {
    var record = record("attachmentV2", orderSnapshot);
    when(client.uploadFile("receipt-voucher-1.png", "attachment", "image/png", PNG)).thenReturn("first-file-code");
    when(client.uploadFile("receipt-voucher-2.jpg", "attachment", "image/jpeg", JPEG))
        .thenThrow(new ApiException(502, "isolated file upload failure")).thenReturn("second-file-code");
    var checkpoints = new ArrayList<ObjectNode>();
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(record, assets, client, value -> checkpoints.add(value.deepCopy())));
    assertEquals(1, plan(record).path("codes").size());
    assertFalse(plan(record).path("ready").asBoolean());
    assertTrue(voucher(record).path("value").isEmpty());
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.beforeCreate(record, ignored -> fail("Incomplete files cannot be marked attempted")));
    assertFalse(FeishuApprovalEvidence.attempted(record));

    var resumed = checkpoints.getLast().deepCopy();
    resumed.put("status", "FAILED");
    plan(resumed).put("preparationFailed", true);
    assertTrue(FeishuApprovalEvidence.retryable(resumed));
    FeishuApprovalEvidence.prepare(resumed, assets, client, value -> checkpoints.add(value.deepCopy()));
    assertTrue(plan(resumed).path("ready").asBoolean());
    assertFalse(plan(resumed).has("preparationFailed"));
    assertEquals(mapper.createArrayNode().add("first-file-code").add("second-file-code"), voucher(resumed).path("value"));
    assertFalse(voucher(resumed).toString().contains(AssetService.PREFIX));
    FeishuApprovalEvidence.beforeCreate(resumed, value -> checkpoints.add(value.deepCopy()));
    assertTrue(FeishuApprovalEvidence.attempted(checkpoints.getLast()));
    assertEquals(resumed.path("form"), checkpoints.getLast().path("form"));
    verify(client).uploadFile("receipt-voucher-1.png", "attachment", "image/png", PNG);
    verify(client, times(2)).uploadFile("receipt-voucher-2.jpg", "attachment", "image/jpeg", JPEG);
    var ids = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(assets, atLeastOnce()).read(ids.capture());
    assertTrue(ids.getAllValues().stream().allMatch(id -> id.matches("[a-f0-9]{64}")));
    verify(client, never()).create(any(), any(), any(), any());
  }

  @ParameterizedTest @ValueSource(strings={"attachmentV2", "image", "imageV2"})
  void attemptedCreationReusesExactFormAndCodesWithoutReadingOrUploadingImagesAgain(String type) {
    var record = record(type, true);
    uploads(type);
    FeishuApprovalEvidence.prepare(record, assets, client, ignored -> {});
    FeishuApprovalEvidence.beforeCreate(record, ignored -> {});
    var frozen = record.deepCopy();
    reset(assets, client);
    var checkpoints = new ArrayList<ObjectNode>();
    FeishuApprovalEvidence.prepare(record, assets, client, value -> checkpoints.add(value.deepCopy()));
    FeishuApprovalEvidence.beforeCreate(record, value -> checkpoints.add(value.deepCopy()));
    assertEquals(frozen, record);
    assertEquals(List.of(frozen, frozen), checkpoints);
    verifyNoInteractions(assets, client);
  }

  @Test void changedFrozenAssetMetadataAndMissingImagesStopPreparationBeforeAnyNativeWrite() {
    var mismatched = record("attachmentV2", true);
    ((ObjectNode) mismatched.path("orderSnapshot").path("attachments").get(0)).put("name", "被改名.png");
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(mismatched, assets, client, ignored -> {}));
    verifyNoInteractions(assets, client);
    when(assets.read(SECOND_ID)).thenThrow(new ApiException(404, "图片不存在"));
    var missing = record("attachmentV2", false);
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(missing, assets, client, ignored -> {}));
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.beforeCreate(missing, ignored -> fail("Missing image cannot start approval")));
    verifyNoInteractions(client);
  }

  @Test void externalAndInlineUrlsNeverReachAssetReadingOrFileUpload() {
    for (String url : List.of("https://example.invalid/private.png", "file:///etc/passwd", "data:image/png;base64,eA==", "/api/assets/../../private")) {
      var record = record("image", false);
      ((ObjectNode) record.path("attachments").get(0)).put("url", url);
      ((ObjectNode) plan(record).path("assets").get(0)).put("url", url);
      assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(record, assets, client, ignored -> {}));
    }
    verifyNoInteractions(assets, client);
  }

  @Test void invalidReturnedFileCodesCannotEnterAnApprovalFormOrMarkCreationAttempted() {
    for (String bad : List.of("", "https://example.invalid/file", "x".repeat(129), "code\r\nInjected")) {
      var record = record("image", false);
      when(client.uploadFile("receipt-voucher-1.png", "image", "image/png", PNG)).thenReturn(bad);
      assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(record, assets, client, ignored -> {}));
      assertTrue(plan(record).path("codes").isEmpty());
      assertTrue(voucher(record).path("value").isEmpty());
      assertThrows(ApiException.class, () -> FeishuApprovalEvidence.beforeCreate(record, ignored -> fail("Invalid code cannot start approval")));
      assertFalse(FeishuApprovalEvidence.attempted(record));
    }
    verify(client, never()).create(any(), any(), any(), any());
  }

  @Test void attemptedFormsWithChangedCodesOrMissingDuplicateOrWrongTypeFieldsCannotBeRebuilt() {
    var record = record("attachmentV2", false);
    uploads("attachmentV2");
    FeishuApprovalEvidence.prepare(record, assets, client, ignored -> {});
    FeishuApprovalEvidence.beforeCreate(record, ignored -> {});
    reset(assets, client);
    var changed = record.deepCopy();
    voucher(changed).putArray("value").add("different-code");
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(changed, assets, client, ignored -> {}));
    var missing = record.deepCopy();
    missing.withArray("form").remove(1);
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(missing, assets, client, ignored -> {}));
    var duplicate = record.deepCopy();
    duplicate.withArray("form").add(voucher(duplicate).deepCopy());
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(duplicate, assets, client, ignored -> {}));
    var wrongType = record.deepCopy();
    voucher(wrongType).put("type", "image");
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(wrongType, assets, client, ignored -> {}));
    var invalid = record.deepCopy();
    plan(invalid).withArray("codes").set(0, mapper.getNodeFactory().textNode("https://not-a-file-code"));
    voucher(invalid).set("value", plan(invalid).path("codes").deepCopy());
    assertThrows(ApiException.class, () -> FeishuApprovalEvidence.prepare(invalid, assets, client, ignored -> {}));
    verifyNoInteractions(assets, client);
  }
}
