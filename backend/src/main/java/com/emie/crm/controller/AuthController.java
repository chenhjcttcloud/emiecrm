package com.emie.crm.controller;

import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.*;
import com.emie.crm.service.SystemService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final AuthService auth;
  private final SystemService system;
  private final CrmRepository db;

  public AuthController(AuthService auth, SystemService system, CrmRepository db) {
    this.auth = auth;
    this.system = system;
    this.db = db;
  }

  @GetMapping("/status")
  public Object status() {
    db.jdbc().queryForObject("SELECT 1", Integer.class);
    return Map.of(
        "loginRequired",
        auth.loginRequired(),
        "setup",
        system.accountCount() > 0,
        "database",
        "mysql",
        "backend",
        "java",
        "testMode",
        "1".equals(System.getenv("CRM_TEST_MODE"))
            && System.getenv().getOrDefault("CRM_DB_NAME", "").endsWith("_test"));
  }

  @PostMapping("/setup")
  public Object setup(@RequestBody JsonNode body) {
    system.setup(body);
    return Map.of("ok", true);
  }

  @PostMapping("/login")
  public Object login(@RequestBody JsonNode body) {
    return auth.login(body);
  }

  @GetMapping("/me")
  public Object me(HttpServletRequest req) { return auth.currentUser(req); }

  @PostMapping("/logout")
  public Object logout(HttpServletRequest req) {
    auth.actor(req);
    auth.logout(req);
    return Map.of("ok", true);
  }

  @GetMapping("/users")
  public Object users(HttpServletRequest req) {
    auth.actor(req).require("system.manage");
    return system.legacyUsers();
  }

  @PostMapping("/users")
  public Object create(@RequestBody JsonNode body, HttpServletRequest req) {
    var actor = auth.actor(req);
    actor.require("system.manage");
    system.legacyCreate(body, actor);
    return system.legacyUsers();
  }
}
