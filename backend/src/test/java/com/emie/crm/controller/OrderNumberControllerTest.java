package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.common.exception.ApiExceptionHandler;
import com.emie.crm.security.Actor;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.OrderNumberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderNumberControllerTest {
  private final AuthService auth=mock(AuthService.class);
  private final OrderNumberService numbers=mock(OrderNumberService.class);
  private final Actor writer=new Actor("sales","销售",Set.of("business.read","business.write"));
  private MockMvc mvc;

  @BeforeEach void setup() {
    when(auth.actor(any())).thenReturn(writer);
    mvc=MockMvcBuilders.standaloneSetup(new OrderNumberController(auth,numbers))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test void returnsOnlyTheReservedBusinessNumberAndDisablesCaching() throws Exception {
    when(numbers.reserve("number-request-000001",writer)).thenReturn(new ObjectMapper().createObjectNode().put("orderNumber","N2611250001"));
    mvc.perform(post("/api/orders/number-reservations").contentType(MediaType.APPLICATION_JSON)
        .content("{\"requestId\":\"number-request-000001\"}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.orderNumber").value("N2611250001")).andExpect(jsonPath("$.actor").doesNotExist());
    verify(numbers).reserve("number-request-000001",writer);
  }

  @Test void malformedJsonFieldsCannotBecomeReservationKeys() throws Exception {
    for(String body:new String[]{"{}","{\"requestId\":null}","{\"requestId\":1234567890123456}","{\"requestId\":[]}","[]"})
      mvc.perform(post("/api/orders/number-reservations").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    verifyNoInteractions(numbers);
  }

  @Test void authenticationAndBothBusinessPermissionsPrecedeAnyAllocation() throws Exception {
    for(Set<String> permissions:java.util.List.of(Set.<String>of(),Set.of("business.read"),Set.of("business.write"))) {
      when(auth.actor(any())).thenReturn(new Actor("blocked","未授权",permissions));
      mvc.perform(post("/api/orders/number-reservations").contentType(MediaType.APPLICATION_JSON)
          .content("{\"requestId\":\"number-request-000001\"}")).andExpect(status().isForbidden());
    }
    when(auth.actor(any())).thenThrow(new ApiException(401,"请先登录"));
    mvc.perform(post("/api/orders/number-reservations").contentType(MediaType.APPLICATION_JSON)
        .content("{}")).andExpect(status().isUnauthorized());
    verifyNoInteractions(numbers);
  }
}
