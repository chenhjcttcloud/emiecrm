package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.SystemService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final AuthService auth;
  private final SystemService service;
  private final com.emie.crm.service.PaymentMethodService paymentMethods;

  public SystemController(AuthService auth, SystemService service, com.emie.crm.service.PaymentMethodService paymentMethods) {
    this.auth = auth;
    this.service = service;
    this.paymentMethods = paymentMethods;
  }

  private com.emie.crm.security.Actor actor(HttpServletRequest req) {
    var actor = auth.actor(req);
    actor.require("system.manage");
    return actor;
  }

  @GetMapping("/payment-methods")
  public Object paymentMethods(HttpServletRequest req) {
    auth.actor(req).require("business.read");
    return paymentMethods.list();
  }
  @PostMapping("/payment-methods")
  public Object savePaymentMethod(@RequestBody JsonNode body, HttpServletRequest req) {
    paymentMethods.save(body, actor(req));
    return paymentMethods.list();
  }

  @GetMapping("/meta")
  public Object meta(HttpServletRequest req) {
    actor(req);
    return Map.of(
        "permissions",
        SystemService.PERMISSIONS.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "label", e.getValue()))
            .toList(),
        "loginRequired",
        auth.loginRequired());
  }

  @GetMapping("/users")
  public Object users(HttpServletRequest req) {
    actor(req);
    return service.users();
  }

  @PostMapping("/users")
  public Object user(@RequestBody JsonNode body, HttpServletRequest req) {
    service.saveUser(body, actor(req));
    return service.users();
  }

  @GetMapping("/roles")
  public Object roles(HttpServletRequest req) {
    actor(req);
    return service.roles();
  }

  @PostMapping("/roles")
  public Object role(@RequestBody JsonNode body, HttpServletRequest req) {
    service.saveRole(body, actor(req));
    return service.roles();
  }

  @GetMapping("/departments")
  public Object departments(HttpServletRequest req) {
    actor(req);
    return service.departments();
  }

  @PostMapping("/departments")
  public Object department(@RequestBody JsonNode body, HttpServletRequest req) {
    service.saveDepartment(body, actor(req));
    return service.departments();
  }

  @GetMapping("/audit")
  public Object audit(HttpServletRequest req) {
    actor(req);
    return service.audit();
  }
}
