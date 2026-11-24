package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.common.exception.ApiExceptionHandler;
import com.emie.crm.security.Actor;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.JushuitanCatalogJob;
import com.emie.crm.service.JushuitanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JushuitanInventoryControllerTest {
  private AuthService auth;
  private JushuitanService service;
  private MockMvc mvc;

  @BeforeEach void setup() {
    auth = mock(AuthService.class); service = mock(JushuitanService.class);
    when(auth.actor(any())).thenReturn(new Actor("reader", "销售", Set.of("business.read")));
    when(service.inventory(anyString(), anyLong())).thenReturn(new ObjectMapper().createArrayNode());
    mvc = MockMvcBuilders.standaloneSetup(new JushuitanController(auth, service, mock(JushuitanCatalogJob.class)))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test void invalidWarehouseIdentifiersNeverReachInventoryService() throws Exception {
    for (String raw : new String[]{"15136229.9", "15136229.0", "1e3", "true", "false", "null",
        "0", "-1", "9223372036854775808", "-9223372036854775809", "[]", "{}", "\"15136229\""})
      mvc.perform(post("/api/jst/inventory").contentType("application/json")
          .content("{\"skuId\":\"A\",\"warehouseId\":" + raw + "}"))
          .andExpect(status().isBadRequest());
    mvc.perform(post("/api/jst/inventory").contentType("application/json").content("{\"skuId\":\"A\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test void positiveIntegerWarehouseIdentifiersReachServiceWithoutPrecisionLoss() throws Exception {
    for (long id : new long[]{1, 15136229, Long.MAX_VALUE}) {
      mvc.perform(post("/api/jst/inventory").contentType("application/json")
          .content("{\"skuId\":\"A\",\"warehouseId\":" + id + "}"))
          .andExpect(status().isOk()).andExpect(content().json("[]"));
      verify(service).inventory("A", id);
    }
  }

  @Test void authenticationAndBusinessReadPermissionStillPrecedeValidation() throws Exception {
    when(auth.actor(any())).thenThrow(new ApiException(401, "请先登录"));
    mvc.perform(post("/api/jst/inventory").contentType("application/json")
        .content("{\"skuId\":\"A\",\"warehouseId\":15136229.9}"))
        .andExpect(status().isUnauthorized());
    doReturn(new Actor("no-business", "无权限", Set.of())).when(auth).actor(any());
    mvc.perform(post("/api/jst/inventory").contentType("application/json")
        .content("{\"skuId\":\"A\",\"warehouseId\":15136229}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }
}
