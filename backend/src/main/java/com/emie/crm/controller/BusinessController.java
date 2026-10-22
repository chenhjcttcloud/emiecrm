package com.emie.crm.controller;

import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.BusinessService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BusinessController {
  private final AuthService auth;
  private final BusinessService service;
  private final CrmRepository db;

  public BusinessController(AuthService auth, BusinessService service, CrmRepository db) {
    this.auth = auth;
    this.service = service;
    this.db = db;
  }

  @GetMapping("/state")
  public JsonNode state(HttpServletRequest req) {
    auth.actor(req).require("business.read");
    return service.state();
  }

  @PostMapping("/state")
  public JsonNode save(@RequestBody JsonNode body, HttpServletRequest req) {
    return service.save(body, auth.actor(req));
  }

  @GetMapping("/receipts")
  public Object receipts(@RequestParam(required = false) Integer page,
      @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String method, HttpServletRequest req) {
    auth.actor(req).require("business.read");
    return page == null ? service.receipts() : service.receiptsPage(page, pageSize, q, method);
  }

  @GetMapping("/audit")
  public Object audit(HttpServletRequest req) {
    auth.actor(req).require("system.manage");
    return db.documents("audit", true, 200);
  }

  @GetMapping("/backup")
  public Object backup(HttpServletRequest req) {
    auth.actor(req).require("data.backup");
    return service.backup();
  }

  @PostMapping("/restore")
  public Object restore(@RequestBody JsonNode body, HttpServletRequest req) {
    var actor = auth.actor(req);
    actor.require("data.backup");
    service.restore(body, actor);
    return Map.of("ok", true);
  }
}
