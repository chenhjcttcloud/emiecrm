package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.common.exception.ApiExceptionHandler;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.BusinessService;
import com.emie.crm.service.JushuitanSettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BusinessStateControllerTest {
  private AuthService auth;
  private BusinessService service;
  private MockMvc mvc;

  @BeforeEach void setup() {
    auth = mock(AuthService.class); service = mock(BusinessService.class);
    when(auth.actor(any())).thenReturn(new Actor("reader", "销售", Set.of("business.read")));
    when(service.state(any())).thenAnswer(call -> new ObjectMapper().createObjectNode()
        .put("unchanged", true).put("revision", (Long) call.getArgument(0)));
    mvc = MockMvcBuilders.standaloneSetup(new BusinessController(auth, service,
        mock(JushuitanSettlementService.class), mock(CrmRepository.class)))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test void conditionalReadIsPrivateAndPassesValidatedVersion() throws Exception {
    mvc.perform(get("/api/state").param("knownRevision", "12"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$.unchanged").value(true)).andExpect(jsonPath("$.revision").value(12));
    verify(service).state(12L);
  }

  @Test void invalidVersionsAreBadRequestsWithoutCallingStateRead() throws Exception {
    for (String value : new String[]{"-1", "+1", "1.0", "1e3", "", " 1", "9223372036854775808", "12345678901234567890"})
      mvc.perform(get("/api/state").param("knownRevision", value)).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test void authenticationAndPermissionChecksPrecedeAnyConditionalRead() throws Exception {
    when(auth.actor(any())).thenThrow(new ApiException(401, "请先登录"));
    mvc.perform(get("/api/state").param("knownRevision", "12")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/state").param("knownRevision", "invalid")).andExpect(status().isUnauthorized());
    doReturn(new Actor("no-business", "无权限", Set.of())).when(auth).actor(any());
    mvc.perform(get("/api/state").param("knownRevision", "12")).andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }
}
