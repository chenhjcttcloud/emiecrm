package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.SalesService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sales")
public class SalesController {
  private final AuthService auth;
  private final SalesService service;

  public SalesController(AuthService auth, SalesService service) {
    this.auth = auth;
    this.service = service;
  }

  @GetMapping("/items")
  public Object items(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
                      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String month,
                      @RequestParam(defaultValue = "") String owner, @RequestParam(defaultValue = "") String customerId,
                      HttpServletRequest request) {
    return service.items(page, pageSize, q, month, owner, customerId, auth.actor(request));
  }

  @GetMapping("/receipts")
  public Object receipts(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
                         @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String month,
                         @RequestParam(defaultValue = "") String owner, @RequestParam(defaultValue = "") String customerId,
                         @RequestParam(defaultValue = "") String method, HttpServletRequest request) {
    return service.receipts(page, pageSize, q, month, owner, customerId, method, auth.actor(request));
  }

  @GetMapping("/targets")
  public Object targets(@RequestParam String month, HttpServletRequest request) {
    return service.targets(month, auth.actor(request));
  }

  @PostMapping("/targets")
  public Object saveTarget(@RequestBody JsonNode body, HttpServletRequest request) {
    return service.saveTarget(body, auth.actor(request));
  }
}
