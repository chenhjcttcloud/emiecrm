package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApiFallbackController {
  private final AuthService auth;

  public ApiFallbackController(AuthService auth) {
    this.auth = auth;
  }

  @RequestMapping("/api/**")
  public ResponseEntity<?> missing(HttpServletRequest request) {
    auth.actor(request);
    return ResponseEntity.status(404).body(Map.of("error", "接口不存在"));
  }
}
