package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.emie.crm.security.FeishuApprovalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JushuitanCreditPushBootstrapTest {
  final ObjectMapper mapper=new ObjectMapper();
  final String config="""
      {"orderId":"order-1","approvalUuid":"uuid-1","instanceCode":"instance-1","shopId":"15",
       "authorizationId":"user-confirmed-20261124","expectedTotal":"12200.00","expectedPaid":0,"accountingMode":"ON_ACCOUNT_TENDER"}
      """;

  @Test void onlyOneExactBoundObjectIsAcceptedAndAmountsRemainExactDecimals() {
    var result=JushuitanCreditPushBootstrap.parse(mapper,config);
    assertEquals("order-1",result.orderId());assertEquals("15",result.shopId());
    assertEquals("12200.00",result.expectedTotal().toPlainString());assertEquals("0",result.expectedPaid().toPlainString());
    for(String invalid:java.util.List.of("["+config+"]", config+" {}", config.replace("\"expectedPaid\":0","\"expectedPaid\":0.001"),
        config.replace("\"shopId\":\"15\"","\"shopId\":15.5"),config.replace("\"expectedPaid\":0","\"expectedPaid\":0,\"expectedPaid\":0"),
        config.replace("ON_ACCOUNT_TENDER","UNCONFIRMED"),config.replace("\"authorizationId\"","\"unexpected\"")))
      assertThrows(RuntimeException.class,()->JushuitanCreditPushBootstrap.parse(mapper,invalid));
  }

  @Test void bootstrapIsDisabledByDefaultAndRequiresTheExplicitAccountingMode() {
    var client=mock(FeishuApprovalClient.class);var queue=mock(JushuitanCreditPushService.class);
    new JushuitanCreditPushBootstrap(client,queue,mapper,"","ON_ACCOUNT_TENDER").start();
    new JushuitanCreditPushBootstrap(client,queue,mapper,config,"").start();
    verifyNoInteractions(client,queue);
  }

  @Test void optionalShippingAuthorizationRequiresExactlyFourCompleteAddressFieldsAndTheOriginalAddress() throws Exception {
    var authorized=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(config);
    authorized.put("expectedOriginalAddress","万科星火");
    authorized.putObject("recipient").put("province","广东省").put("city","深圳市").put("district","龙岗区").put("address","万科星火online10栋");
    var parsed=JushuitanCreditPushBootstrap.parse(mapper,authorized.toString());
    assertEquals("万科星火",parsed.expectedOriginalAddress());assertEquals("龙岗区",parsed.recipient().path("district").asText());
    for(String field:java.util.List.of("province","city","district","address")) {
      var invalid=authorized.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("recipient")).remove(field);
      assertThrows(RuntimeException.class,()->JushuitanCreditPushBootstrap.parse(mapper,invalid.toString()));
    }
    var missingOriginal=authorized.deepCopy();missingOriginal.remove("expectedOriginalAddress");
    assertThrows(RuntimeException.class,()->JushuitanCreditPushBootstrap.parse(mapper,missingOriginal.toString()));
    var phone=authorized.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)phone.path("recipient")).put("phone","13800000000");
    assertThrows(RuntimeException.class,()->JushuitanCreditPushBootstrap.parse(mapper,phone.toString()));
  }

  @Test void anAuthenticatedNativeReadPrecedesTheSingleInternalAuthorizationCall() {
    var client=mock(FeishuApprovalClient.class);var queue=mock(JushuitanCreditPushService.class);
    when(client.identityReady()).thenReturn(true);
    var instance=mapper.createObjectNode().put("status","APPROVED");
    when(client.instance("instance-1")).thenReturn(instance);
    when(queue.bootstrap(any(),same(instance))).thenReturn(mapper.createObjectNode().put("status","QUEUED"));
    var component=new JushuitanCreditPushBootstrap(client,queue,mapper,config,"ON_ACCOUNT_TENDER");
    component.start();component.start();
    var ordered=inOrder(client,queue);ordered.verify(client).identityReady();ordered.verify(client).instance("instance-1");
    ordered.verify(queue).bootstrap(any(),same(instance));verifyNoMoreInteractions(queue);
  }

  @Test void nativeReadFailureIsFailClosedAndCannotPreventApplicationStartup() {
    var client=mock(FeishuApprovalClient.class);var queue=mock(JushuitanCreditPushService.class);
    when(client.identityReady()).thenReturn(true);when(client.instance("instance-1")).thenThrow(new IllegalStateException("isolated timeout"));
    assertDoesNotThrow(()->new JushuitanCreditPushBootstrap(client,queue,mapper,config,"ON_ACCOUNT_TENDER").start());
    verifyNoInteractions(queue);
  }
}
