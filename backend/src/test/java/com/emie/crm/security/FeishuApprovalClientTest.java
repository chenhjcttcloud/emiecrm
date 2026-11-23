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
          when(response.statusCode()).thenReturn(200);
          when(response.body()).thenReturn((String) next);
          return response;
        });
  }

  private FeishuApprovalClient client(String sales, String prepayment) {
    return new FeishuApprovalClient(identity, mapper, "test-secret", sales, prepayment, http);
  }

  private void authorized() {
    responses.add("{\"code\":0,\"tenant_access_token\":\"test-token\",\"expire\":7200}");
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
    var result = new CompletableFuture<String>();
    var bytes = new ByteArrayOutputStream();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
      public void onNext(ByteBuffer buffer) {
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      public void onError(Throwable error) { result.completeExceptionally(error); }
      public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
    });
    return result.join();
  }
}
