package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.SalesTargetScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sales/target-scope")
public class SalesTargetScopeController {
  private final AuthService auth;
  private final SalesTargetScopeService service;

  public SalesTargetScopeController(AuthService auth, SalesTargetScopeService service) {
    this.auth = auth;
    this.service = service;
  }

  @GetMapping
  public Object view(HttpServletRequest request) { return service.view(auth.actor(request)); }

  @PostMapping
  public Object save(@RequestBody JsonNode body, HttpServletRequest request) { return service.save(body, auth.actor(request)); }
}
