package com.emie.crm.security;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class FeishuLongConnectionTest {
 @Test void disabledModeDoesNotContactFeishu(){
  var client=mock(FeishuClient.class);var channel=new FeishuLongConnection(client,"0");channel.start();assertEquals("disabled",channel.status());verifyNoInteractions(client);channel.stop();
 }
 @Test void configuredClientStartsOffThreadAndClosesOnShutdown() throws Exception {
  var client=mock(FeishuClient.class);var websocket=mock(com.lark.oapi.ws.Client.class);
  when(client.ready()).thenReturn(true);when(client.longConnection(any(),any(),any())).thenReturn(websocket);
  var channel=new FeishuLongConnection(client,"1");
  try{channel.start();verify(websocket,timeout(2000)).awaitReady(15000);}
  finally{channel.stop();}
  verify(websocket,atLeastOnce()).close();assertEquals("stopped",channel.status());
 }
 @Test void missingCredentialsDoNotCreateAChannel(){
  var client=mock(FeishuClient.class);var channel=new FeishuLongConnection(client,"1");channel.start();assertEquals("unconfigured",channel.status());verify(client,never()).longConnection(any(),any(),any());channel.stop();
 }
}
