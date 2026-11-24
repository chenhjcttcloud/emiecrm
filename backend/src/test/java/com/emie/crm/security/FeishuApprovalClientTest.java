package com.emie.crm.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeishuApprovalClientTest {
  private final FeishuClient identity = mock(FeishuClient.class);
  private final HttpClient http = mock(HttpClient.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final ArrayDeque<Object> responses = new ArrayDeque<>();
  private final List<HttpRequest> requests = new ArrayList<>();

  @BeforeEach void setup() throws Exception {
    when(identity.ready()).thenReturn(true);
    when(identity.appId()).thenReturn("crm-app");
    when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenAnswer(invocation -> {
          requests.add(invocation.getArgument(0));
          Object next = responses.removeFirst();
          if (next instanceof Exception failure) throw failure;
          @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
          when(response.statusCode()).thenReturn(next instanceof StubResponse stub ? stub.status() : 200);
          when(response.body()).thenReturn(next instanceof StubResponse stub ? stub.body() : (String) next);
          return response;
        });
  }

  private FeishuApprovalClient client(String sales, String prepayment) {
    return new FeishuApprovalClient(identity, mapper, "test-secret", sales, prepayment, http);
  }

  private void authorized() {
    responses.add("{\"code\":0,\"tenant_access_token\":\"test-token\",\"expire\":7200}");
  }

  private record StubResponse(int status, String body) {}

  private static byte[] png() {
    return new byte[]{(byte)137,80,78,71,13,10,26,10,0,(byte)255,0,42};
  }

  @Test void fileUploadUsesTheApprovalEndpointMultipartContractAndPreservesBinaryContent() {
    var client = client("sales-code", "prepayment-code");
    authorized();
    responses.add("{\"code\":0,\"data\":{\"code\":\"D93653C3-2609-4EE0-8041-61DC1D84F0B5\",\"url\":\"https://temporary.example/image\"}}");
    responses.add("{\"code\":0,\"data\":{\"code\":\"second-image-code\"}}");
    byte[] image = png();
    assertEquals("D93653C3-2609-4EE0-8041-61DC1D84F0B5", client.uploadFile("收款凭证.png", "attachment", "image/png", image));
    assertEquals("second-image-code", client.uploadFile("deposit.png", "image", "image/png", image));
    assertEquals(3, requests.size(), "Both uploads reuse the same tenant token");
    var upload = requests.get(1);
    assertEquals("POST", upload.method());
    assertEquals("https://www.feishu.cn/approval/openapi/v2/file/upload", upload.uri().toString());
    assertEquals("Bearer test-token", upload.headers().firstValue("Authorization").orElseThrow());
    String contentType = upload.headers().firstValue("Content-Type").orElseThrow();
    assertTrue(contentType.startsWith("multipart/form-data; boundary=crm-approval-"));
    String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
    byte[] wire = bodyBytes(upload);
    String text = new String(wire, StandardCharsets.UTF_8);
    assertTrue(text.contains("name=\"name\"\r\n\r\n收款凭证.png\r\n--" + boundary));
    assertTrue(text.contains("name=\"type\"\r\n\r\nattachment\r\n--" + boundary));
    assertTrue(text.contains("name=\"content\"; filename=\"收款凭证.png\"\r\nContent-Type: image/png\r\n\r\n"));
    String bytesAsLatin = new String(wire, StandardCharsets.ISO_8859_1);
    int start = bytesAsLatin.indexOf("\r\n\r\n", bytesAsLatin.indexOf("name=\"content\"")) + 4;
    int end = bytesAsLatin.lastIndexOf("\r\n--" + boundary + "--\r\n");
    assertArrayEquals(image, Arrays.copyOfRange(wire, start, end));
    image[8] = 99;
    assertEquals(0, bodyBytes(upload)[start + 8], "The request owns immutable image bytes");
    assertTrue(body(requests.get(2)).contains("name=\"type\"\r\n\r\nimage\r\n"));
    assertTrue(requests.stream().noneMatch(request -> request.uri().getPath().contains("instances")));
  }

  @Test void imageUploadAcceptsOnlyTheFourSupportedSignaturesAndMatchingFileExtensions() {
    var client = client("sales-code", "");
    authorized();
    var names = List.of("proof.JPG", "proof.jpeg", "proof.gif", "proof.webp");
    var types = List.of("image/jpeg", "image/jpeg", "image/gif", "image/webp");
    var bytes = List.of(new byte[]{(byte)255,(byte)216,(byte)255,0}, new byte[]{(byte)255,(byte)216,(byte)255,0},
        "GIF89aexample".getBytes(StandardCharsets.US_ASCII), "RIFF0000WEBPexample".getBytes(StandardCharsets.US_ASCII));
    for (int index = 0; index < names.size(); index++) {
      responses.add("{\"code\":0,\"data\":{\"code\":\"file-" + index + "\"}}");
      assertEquals("file-" + index, client.uploadFile(names.get(index), "attachment", types.get(index), bytes.get(index)));
    }
    assertEquals(5, requests.size());
  }

  @Test void invalidUploadInputsCannotAcquireTokenOrSendAnHttpRequest() {
    var client = client("sales-code", "");
    for (String name : Arrays.asList(null, "", "../proof.png", "proof\\test.png", "proof\".png", "proof\r\nX-Header:bad.png",
        "proof\u0000.png", " proof.png", "p".repeat(201) + ".png", "proof.jpg", "proof"))
      assertThrows(ApiException.class, () -> client.uploadFile(name, "attachment", "image/png", png()));
    for (String type : Arrays.asList(null, "file", "image\r\nInjected"))
      assertThrows(ApiException.class, () -> client.uploadFile("proof.png", type, "image/png", png()));
    for (String type : Arrays.asList(null, "application/octet-stream", "image/jpeg", "image/png\r\nInjected"))
      assertThrows(ApiException.class, () -> client.uploadFile("proof.png", "image", type, png()));
    assertThrows(ApiException.class, () -> client.uploadFile("proof.png", "image", "image/png", null));
    assertThrows(ApiException.class, () -> client.uploadFile("proof.png", "image", "image/png", new byte[0]));
    assertThrows(ApiException.class, () -> client.uploadFile("proof.png", "image", "image/png", new byte[5 * 1024 * 1024 + 1]));
    assertThrows(ApiException.class, () -> client.uploadFile("proof.png", "image", "image/png", "not a PNG".getBytes(StandardCharsets.UTF_8)));
    assertTrue(requests.isEmpty());
  }

  @Test void uploadFailuresNeverBecomeInstanceUncertaintyOrExposeUpstreamDetailsAndAreNotRetried() {
    var client = client("sales-code", "");
    authorized();
    List<Object> failures = List.of(
        "{\"code\":99991672,\"msg\":\"private-upstream-message\"}",
        "{\"code\":0,\"data\":{}}", "{\"code\":0,\"data\":{\"code\":123}}",
        "{\"code\":0,\"data\":{\"code\":\"http://untrusted/file\"}}",
        "{\"code\":0,\"data\":{\"code\":\"" + "x".repeat(129) + "\"}}",
        "{\"code\":4294967296,\"data\":{\"code\":\"unexpected\"}}", "[]", "not json",
        new StubResponse(302, "{\"code\":0,\"data\":{\"code\":\"redirected\"}}"),
        new StubResponse(500, "{\"code\":0,\"data\":{\"code\":\"unexpected\"}}"),
        new IOException("private-token-and-file-bytes"));
    for (Object response : failures) {
      responses.add(response);
      var failure = assertThrows(ApiException.class, () -> client.uploadFile("proof.png", "attachment", "image/png", png()));
      assertEquals(ApiException.class, failure.getClass());
      assertEquals(502, failure.status());
      assertEquals("飞书凭证上传未完成，尚未发起本次审批，请重试", failure.getMessage());
    }
    assertEquals(failures.size() + 1, requests.size());
    assertTrue(requests.stream().noneMatch(request -> request.uri().getPath().contains("instances")));
  }

  @Test void interruptedUploadRestoresInterruptAndLeavesInstanceCreationUntouched() {
    var client = client("sales-code", "");
    authorized();
    responses.add(new InterruptedException("private-interrupt-details"));
    try {
      assertEquals(ApiException.class, assertThrows(ApiException.class,
          () -> client.uploadFile("proof.png", "image", "image/png", png())).getClass());
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(2, requests.size());
    } finally {
      Thread.interrupted();
    }
  }

  @Test void definitionsAreIndependentAndTrimmed() {
    var client = client(" sales-code ", " prepayment-code ");
    assertEquals("sales-code", client.approvalCode("销售订单"));
    assertEquals("prepayment-code", client.approvalCode("预收款订单"));
    assertTrue(client.ready("销售订单"));
    assertTrue(client.ready("预收款订单"));
    assertEquals("sales-code", client.approvalCode("海外提货单"));
    assertTrue(client.ready("海外提货单"));
    assertFalse(client("sales-code", "").enabled("预收款订单"));
    assertFalse(client("", "prepayment-code").enabled("销售订单"));
    assertFalse(client("", "prepayment-code").enabled("海外提货单"));
    assertThrows(IllegalArgumentException.class, () -> client("bad/code", ""));
    assertThrows(IllegalArgumentException.class, () -> client("", "bad/code"));
    assertTrue(requests.isEmpty());
  }

  @Test void historicalInstancesRemainReadableWhenNewSubmissionsAreDisabled() {
    var client = client("", "");
    assertTrue(client.identityReady());
    assertFalse(client.enabled());
    assertFalse(client.ready());
    authorized();
    responses.add("{\"code\":0,\"data\":{\"instance_code\":\"previous-instance\"}}");
    assertEquals("previous-instance", client.instance("previous-instance").path("instance_code").asText());
  }

  @Test void receiptDefinitionDoesNotEnableOrReplaceEitherOrderDefinition() {
    var client = new FeishuApprovalClient(identity, mapper, "test-secret", "", "", " receipt-code ", http);
    assertTrue(client.receiptEnabled());
    assertTrue(client.receiptReady());
    assertEquals("receipt-code", client.receiptApprovalCode());
    assertFalse(client.enabled());
    assertFalse(client.ready("销售订单"));
    assertFalse(client.ready("预收款订单"));
    assertFalse(client("sales-code", "prepayment-code").receiptEnabled());
    when(identity.ready()).thenReturn(false);
    assertFalse(client.receiptReady());
    assertThrows(IllegalArgumentException.class,
        () -> new FeishuApprovalClient(identity, mapper, "test-secret", "sales-code", "prepayment-code", "bad/code", http));
    assertTrue(requests.isEmpty());
  }

  @Test void receiptCreationUsesItsOwnDefinitionAndPersistedUuid() throws Exception {
    var client = new FeishuApprovalClient(identity, mapper, "test-secret", "sales-code", "prepayment-code", "receipt-code", http);
    authorized();
    responses.add("{\"code\":0,\"data\":{\"instance_code\":\"receipt-instance\"}}");
    assertEquals("receipt-instance", client.create(client.receiptApprovalCode(), "ou_actor", "receipt-uuid", mapper.createArrayNode()));
    var submitted = mapper.readTree(body(requests.get(1)));
    assertEquals("receipt-code", submitted.path("approval_code").asText());
    assertEquals("receipt-uuid", submitted.path("uuid").asText());
    assertEquals("sales-code", client.approvalCode("销售订单"));
    assertEquals("prepayment-code", client.approvalCode("预收款订单"));
  }

  @Test void createSendsThePersistedUuidAndSerializedFormAndReusesToken() throws Exception {
    var client = client("sales-code", "");
    var form = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "amount").put("value", "120.00"));
    authorized();
    responses.add("{\"code\":0,\"data\":{\"instance_code\":\"instance-1\"}}");
    responses.add("{\"code\":0,\"data\":{\"instance_code\":\"instance-2\"}}");
    assertEquals("instance-1", client.create("sales-code", "ou_actor", "persisted-uuid", form));
    assertEquals("instance-2", client.create("sales-code", "ou_actor", "second-uuid", form));
    assertEquals(3, requests.size());
    var create = requests.get(1);
    assertEquals("POST", create.method());
    assertEquals("https://open.feishu.cn/open-apis/approval/v4/instances", create.uri().toString());
    assertEquals("Bearer test-token", create.headers().firstValue("Authorization").orElseThrow());
    var body = mapper.readTree(body(create));
    assertEquals("persisted-uuid", body.path("uuid").asText());
    assertEquals("ou_actor", body.path("open_id").asText());
    assertEquals("sales-code", body.path("approval_code").asText());
    assertEquals(form, mapper.readTree(body.path("form").asText()));
  }

  @Test void duplicateUuidRecoversOnlyTheSameDefinitionAndInitiator() {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":60012}");
    responses.add("{\"code\":0,\"data\":{\"instance_code\":\"existing\",\"uuid\":\"persisted-uuid\","
        + "\"approval_code\":\"sales-code\",\"open_id\":\"ou_actor\"}}");
    assertEquals("existing", client.create("sales-code", "ou_actor", "persisted-uuid", mapper.createArrayNode()));
    assertTrue(requests.get(2).uri().getPath().endsWith("/persisted-uuid"));
  }

  @Test void duplicateUuidWithDifferentInitiatorRemainsUncertain() {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":60012}");
    responses.add("{\"code\":0,\"data\":{\"instance_code\":\"foreign\",\"uuid\":\"persisted-uuid\","
        + "\"approval_code\":\"sales-code\",\"open_id\":\"ou_someone_else\"}}");
    assertThrows(FeishuApprovalClient.Uncertain.class,
        () -> client.create("sales-code", "ou_actor", "persisted-uuid", mapper.createArrayNode()));
    assertEquals(3, requests.size());
  }

  @Test void transportFailureNeverResubmitsOrExposesUpstreamSecrets() {
    var client = client("sales-code", "");
    authorized();
    responses.add(new IOException("upstream secret response"));
    var failure = assertThrows(FeishuApprovalClient.Uncertain.class,
        () -> client.create("sales-code", "ou_actor", "persisted-uuid", mapper.createArrayNode()));
    assertFalse(failure.getMessage().contains("secret"));
    assertEquals(2, requests.size());
  }

  @Test void explicitFormRejectionCanBeDistinguishedFromUncertainResult() {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":60006,\"msg\":\"sensitive upstream details\"}");
    var failure = assertThrows(FeishuApprovalClient.Rejected.class,
        () -> client.create("sales-code", "ou_actor", "persisted-uuid", mapper.createArrayNode()));
    assertTrue(failure.getMessage().contains("60006"));
    assertFalse(failure.getMessage().contains("sensitive"));
  }

  @Test void inactiveDefinitionCannotBeUsedToBuildAnApproval() {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":0,\"data\":{\"approval_code\":\"sales-code\",\"status\":\"INACTIVE\"}}");
    assertEquals(503, assertThrows(ApiException.class, () -> client.definition()).status());
  }

  @Test void invalidInstanceIdsAndCreationKeysNeverReachNetwork() {
    var client = client("sales-code", "");
    assertThrows(ApiException.class, () -> client.instance("x?access_token=bad"));
    assertThrows(ApiException.class, () -> client.create("sales-code", "bad/id", "uuid", mapper.createArrayNode()));
    assertThrows(ApiException.class, () -> client.create("sales-code", "ou_actor", "x".repeat(65), mapper.createArrayNode()));
    assertTrue(requests.isEmpty());
  }

  @Test void notFoundRemainsDistinctForReconciliation() {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":60003}");
    assertThrows(FeishuApprovalClient.NotFound.class, () -> client.instance("persisted-uuid"));
  }

  @Test void previewUsesExplicitOpenIdIdentity() throws Exception {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":0,\"data\":{\"preview_nodes\":[{\"node_id\":\"next\"}]}}");
    assertEquals("next", client.previewAfter("instance", "task", "ou_approver").get(0).path("node_id").asText());
    var preview = requests.get(1);
    assertEquals("user_id_type=open_id", preview.uri().getQuery());
    assertEquals("ou_approver", mapper.readTree(body(preview)).path("user_id").asText());
  }

  @Test void namesForPeopleOutsideCrmAreBatchedCachedAndLimitedToRequestedIds() {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":0,\"data\":{\"items\":[{\"open_id\":\"ou_approver\",\"name\":\"审批人\"},"
        + "{\"open_id\":\"ou_unrequested\",\"name\":\"不应保留\"}]}}");
    var expected = java.util.Map.of("ou_approver", "审批人");
    assertEquals(expected, client.userNames(Set.of("ou_approver", "ou_missing", "bad/id")));
    assertEquals(expected, client.userNames(Set.of("ou_approver", "ou_missing")));
    assertEquals(2, requests.size());
    assertTrue(requests.get(1).uri().getPath().endsWith("/users/batch"));
    assertTrue(requests.get(1).uri().getQuery().contains("user_id_type=open_id"));
    assertTrue(requests.get(1).uri().getQuery().contains("user_ids=ou_approver"));
    assertTrue(requests.get(1).uri().getQuery().contains("user_ids=ou_missing"));
  }

  @Test void missingContactsPermissionDoesNotBlockApprovalStatusOrRepeatLookup() {
    var client = client("sales-code", "");
    authorized();
    responses.add("{\"code\":99991672,\"msg\":\"permission denied\"}");
    assertTrue(client.userNames(Set.of("ou_approver")).isEmpty());
    assertTrue(client.userNames(Set.of("ou_approver")).isEmpty());
    responses.add("{\"code\":0,\"data\":{\"instance_code\":\"instance\",\"status\":\"APPROVED\"}}");
    assertEquals("APPROVED", client.instance("instance").path("status").asText());
    assertEquals(3, requests.size());
  }

  private static String body(HttpRequest request) {
    return new String(bodyBytes(request), StandardCharsets.UTF_8);
  }

  private static byte[] bodyBytes(HttpRequest request) {
    var result = new CompletableFuture<byte[]>();
    var bytes = new ByteArrayOutputStream();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
      public void onNext(ByteBuffer buffer) {
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      public void onError(Throwable error) { result.completeExceptionally(error); }
      public void onComplete() { result.complete(bytes.toByteArray()); }
    });
    return result.join();
  }
}
