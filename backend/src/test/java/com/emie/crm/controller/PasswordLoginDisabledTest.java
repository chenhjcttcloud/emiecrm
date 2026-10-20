package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiExceptionHandler;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.SystemService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordLoginDisabledTest {
  @Test void disabledPasswordLoginAndPublicSetupCannotBeUsedDirectly() throws Exception {
    var auth=mock(AuthService.class);var system=mock(SystemService.class);
    var mvc=MockMvcBuilders.standaloneSetup(new AuthController(auth,system,mock(CrmRepository.class),"0")).setControllerAdvice(new ApiExceptionHandler()).build();
    for(String path:new String[]{"login","setup"})
      mvc.perform(post("/api/"+path).contentType("application/json").content("{\"name\":\"admin\",\"password\":\"test-password\"}"))
          .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("账号密码登录已关闭，请使用飞书登录"));
    verifyNoInteractions(auth,system);
  }
}
