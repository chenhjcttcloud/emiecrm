package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.DashboardService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
  private final AuthService auth;
  private final DashboardService service;

  public DashboardController(AuthService auth, DashboardService service) {
    this.auth = auth;
    this.service = service;
  }

  @GetMapping("/sales")
  public Object sales(@RequestParam String month, @RequestParam(defaultValue = "") String owner,
                      @RequestParam(defaultValue = "") String customerId, HttpServletRequest request) {
    return service.sales(month, owner, customerId, auth.actor(request));
  }

  @GetMapping("/targets")
  public Object targets(@RequestParam int year, HttpServletRequest request) {
    return service.targets(year, auth.actor(request));
  }

  @PostMapping("/targets")
  public Object saveTarget(@RequestBody JsonNode body, HttpServletRequest request) {
    return service.saveTarget(body, auth.actor(request));
  }
}
