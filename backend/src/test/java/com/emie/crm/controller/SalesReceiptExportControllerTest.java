package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.common.exception.ApiExceptionHandler;
import com.emie.crm.security.Actor;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.SalesService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SalesReceiptExportControllerTest {
  private AuthService auth;
  private SalesService service;
  private MockMvc mvc;
  private final Actor reader = new Actor("reader", "销售", Set.of("business.read"));

  @BeforeEach void setup() {
    auth = mock(AuthService.class); service = mock(SalesService.class);
    when(auth.actor(any())).thenReturn(reader);
    mvc = MockMvcBuilders.standaloneSetup(new SalesController(auth, service))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test void exportRoutePassesEveryFilterWithoutUsingThePaginatedHandler() throws Exception {
    when(service.exportReceipts("到账", "2026-11", "小王", "c1", "银行转账", reader))
        .thenReturn(Map.of("rows", List.of(Map.of("id", "receipt-1")), "total", 1));
    mvc.perform(get("/api/sales/receipts/export").param("q", "到账").param("month", "2026-11")
        .param("owner", "小王").param("customerId", "c1").param("method", "银行转账"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].id").value("receipt-1"));
    verify(service).exportReceipts("到账", "2026-11", "小王", "c1", "银行转账", reader);
    verifyNoMoreInteractions(service);
  }

  @Test void anonymousExportIsRejectedBeforeQueryingReceipts() throws Exception {
    when(auth.actor(any())).thenThrow(new ApiException(401, "请先登录"));
    mvc.perform(get("/api/sales/receipts/export")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }
}
