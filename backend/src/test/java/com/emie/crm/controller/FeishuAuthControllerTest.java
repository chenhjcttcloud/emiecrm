package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiExceptionHandler;
import com.emie.crm.security.*;
import com.lark.oapi.service.authen.v1.model.GetUserInfoRespBody;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class FeishuAuthControllerTest {
  FeishuClient client; FeishuIdentityService identities; AuthService auth; MockMvc mvc;
  @BeforeEach void init() {
    client=mock(FeishuClient.class);identities=mock(FeishuIdentityService.class);auth=mock(AuthService.class);
    when(client.ready()).thenReturn(true);when(client.appId()).thenReturn("test-app");when(client.redirectUri()).thenReturn("https://crm.example/api/auth/feishu/callback");
    mvc=MockMvcBuilders.standaloneSetup(new FeishuAuthController(client,identities,auth,new FeishuLongConnection(client,"0"))).setControllerAdvice(new ApiExceptionHandler()).build();
  }
  @Test void publicConfigDoesNotExposeCredentials() throws Exception {
    mvc.perform(get("/api/auth/feishu/config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.appSecret").doesNotExist());
  }
  @Test void invalidOrExpiredStateNeverExchangesCode() throws Exception {
    var session=new MockHttpSession();session.setAttribute("feishu.state","valid");session.setAttribute("feishu.state.expires",System.currentTimeMillis()+10000);
    mvc.perform(get("/api/auth/feishu/callback").session(session).param("code","code").param("state","invalid")).andExpect(status().isFound()).andExpect(header().string("Location",org.hamcrest.Matchers.containsString("sso_error=")));
    assertEquals("valid",session.getAttribute("feishu.state"));verify(client,never()).identity(anyString());
    session.setAttribute("feishu.state","valid");session.setAttribute("feishu.state.expires",0L);
    mvc.perform(get("/api/auth/feishu/callback").session(session).param("code","code").param("state","valid")).andExpect(status().isFound());
    verify(client,never()).identity(anyString());
  }
  @Test void ticketIsBrowserBoundSingleUseAndDoesNotContainBearerToken() throws Exception {
    var session=new MockHttpSession();session.setAttribute("feishu.state","valid");session.setAttribute("feishu.state.expires",System.currentTimeMillis()+10000);
    var info=mock(GetUserInfoRespBody.class);when(client.identity("code")).thenReturn(info);when(identities.resolve("test-app",info)).thenReturn("member");
    when(auth.createSession("member")).thenReturn(Map.of("token","private-bearer-token"));
    var response=mvc.perform(get("/api/auth/feishu/callback").session(session).param("code","code").param("state","valid")).andExpect(status().isFound()).andReturn().getResponse();
    String location=response.getHeader("Location");assertTrue(location.startsWith("/?sso_ticket="));assertFalse(location.contains("private-bearer-token"));
    String body="{\"ticket\":\""+session.getAttribute("feishu.ticket")+"\"}";
    mvc.perform(post("/api/auth/feishu/exchange").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.token").value("private-bearer-token"));
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    verify(auth,times(1)).createSession("member");
  }
  @Test void disabledConfigurationCannotStartLogin() throws Exception {
    when(client.ready()).thenReturn(false);
    mvc.perform(get("/api/auth/feishu/start")).andExpect(status().isBadRequest());
  }
  @Test void olderCallbackDoesNotInvalidateTheCurrentLoginAttempt() throws Exception {
    var session=new MockHttpSession();session.setAttribute("feishu.state","current");session.setAttribute("feishu.state.expires",System.currentTimeMillis()+10000);
    mvc.perform(get("/api/auth/feishu/callback").session(session).param("code","old-code").param("state","older"))
        .andExpect(status().isFound()).andExpect(header().string("Location",org.hamcrest.Matchers.containsString("sso_error=")));
    assertEquals("current",session.getAttribute("feishu.state"));
    var info=mock(GetUserInfoRespBody.class);when(client.identity("current-code")).thenReturn(info);when(identities.resolve("test-app",info)).thenReturn("member");
    mvc.perform(get("/api/auth/feishu/callback").session(session).param("code","current-code").param("state","current"))
        .andExpect(status().isFound()).andExpect(header().string("Location",org.hamcrest.Matchers.containsString("sso_ticket=")));
    verify(client,never()).identity("old-code");verify(client).identity("current-code");
  }
  @Test void incorrectTicketDoesNotBurnTheValidBrowserBoundTicket() throws Exception {
    var session=new MockHttpSession();session.setAttribute("feishu.ticket","current-ticket");session.setAttribute("feishu.user","member");session.setAttribute("feishu.ticket.expires",System.currentTimeMillis()+10000);
    when(auth.createSession("member")).thenReturn(Map.of("token","private-bearer-token"));
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content("{\"ticket\":\"older-ticket\"}"))
        .andExpect(status().isUnauthorized());
    assertEquals("current-ticket",session.getAttribute("feishu.ticket"));
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content("{\"ticket\":\"current-ticket\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content("{\"ticket\":\"current-ticket\"}"))
        .andExpect(status().isUnauthorized());
    verify(auth,times(1)).createSession("member");
  }

  @Test void failedSessionCreationCanRetryWithoutConsumingTheTicket() throws Exception {
    var session=new MockHttpSession();session.setAttribute("feishu.ticket","ticket");session.setAttribute("feishu.user","member");session.setAttribute("feishu.ticket.expires",System.currentTimeMillis()+10000);
    when(auth.createSession("member")).thenThrow(new com.emie.crm.common.exception.ApiException(503,"服务暂不可用"))
        .thenReturn(Map.of("token","new-token"));
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content("{\"ticket\":\"ticket\"}"))
        .andExpect(status().isServiceUnavailable());
    assertEquals("ticket",session.getAttribute("feishu.ticket"));
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content("{\"ticket\":\"ticket\"}"))
        .andExpect(status().isOk());
    assertNull(session.getAttribute("feishu.ticket"));
  }
  @Test void expiredTicketStillCannotCreateASession() throws Exception {
    var session=new MockHttpSession();session.setAttribute("feishu.ticket","expired");session.setAttribute("feishu.user","member");session.setAttribute("feishu.ticket.expires",0L);
    mvc.perform(post("/api/auth/feishu/exchange").session(session).contentType("application/json").content("{\"ticket\":\"expired\"}"))
        .andExpect(status().isUnauthorized());
    assertNull(session.getAttribute("feishu.ticket"));verify(auth,never()).createSession(anyString());
  }

}
