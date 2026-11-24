package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiExceptionHandler;
import com.emie.crm.security.Actor;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.JushuitanCreditPushService;
import com.emie.crm.service.JushuitanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JushuitanCreditPushControllerTest {
  final AuthService auth = mock(AuthService.class);
  final JushuitanService jst = mock(JushuitanService.class);
  final JushuitanCreditPushService queue = mock(JushuitanCreditPushService.class);
  final JushuitanCreditPushService.Transport transport = mock(JushuitanCreditPushService.Transport.class);

  private org.springframework.test.web.servlet.MockMvc mvc(String mode) {
    return MockMvcBuilders.standaloneSetup(new JushuitanCreditPushController(auth,jst,queue,transport,mode))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test void disabledCreditContractDoesNotQueryUpstreamOptions() throws Exception {
    when(auth.actor(any())).thenReturn(new Actor("reader","销售",Set.of("business.read")));
    when(transport.ready()).thenReturn(true);
    mvc("UNCONFIGURED").perform(get("/api/jst/credit-shipping-options"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.accountingMode").value("UNCONFIGURED"));
    verifyNoInteractions(jst);
  }

  @Test void readyContractReturnsExplicitNoncashAccountingAndVerifiedChoices() throws Exception {
    when(auth.actor(any())).thenReturn(new Actor("reader","销售",Set.of("business.read")));
    when(transport.ready()).thenReturn(true);
    when(jst.shops()).thenReturn(List.of(java.util.Map.of("id",18830986,"name","店铺")));
    when(jst.warehouses()).thenReturn(new ObjectMapper().readTree("[{\"id\":15136229,\"name\":\"仓库\"}]"));
    mvc("ON_ACCOUNT_TENDER").perform(get("/api/jst/credit-shipping-options"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.accountingMode").value("ON_ACCOUNT_TENDER"))
        .andExpect(jsonPath("$.shops[0].id").value(18830986))
        .andExpect(jsonPath("$.warehouses[0].id").value(15136229));
  }

  @Test void optionsRequireAuthenticatedBusinessReadBeforeQuery() throws Exception {
    when(auth.actor(any())).thenReturn(new Actor("none","销售",Set.of()));
    mvc("ON_ACCOUNT_TENDER").perform(get("/api/jst/credit-shipping-options"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(jst,queue,transport);
  }
}
