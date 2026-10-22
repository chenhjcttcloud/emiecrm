package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.JushuitanService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jst")
public class JushuitanController {
  private final AuthService auth;
  private final JushuitanService service;
  public JushuitanController(AuthService auth, JushuitanService service) { this.auth = auth; this.service = service; }
  @GetMapping("/config") public Object config(HttpServletRequest req) { auth.actor(req).require("business.read"); return service.config(); }
  @GetMapping("/shipments") public Object list(HttpServletRequest req) { auth.actor(req).require("business.read"); return service.list(); }
  @GetMapping("/shops") public Object shops(HttpServletRequest req) { auth.actor(req).require("order.approve"); return service.shops(); }
  @PostMapping("/inventory") public Object inventory(@RequestBody JsonNode body, HttpServletRequest req) {
    auth.actor(req).require("business.read"); return service.inventory(body.path("skuId").asText());
  }
  @PostMapping("/orders/{id}/submit") public Object submit(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    return service.submit(id, body, auth.actor(req));
  }
  @PostMapping("/orders/{id}/change") public Object change(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    return service.change(id, body, auth.actor(req));
  }
  @PostMapping("/orders/{id}/amend") public Object amend(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    return service.amend(id, body, auth.actor(req));
  }
  @PostMapping("/orders/{id}/amend/continue") public Object continueAmendment(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    return service.continueAmendment(id, body, auth.actor(req));
  }
  @PostMapping("/orders/{id}/sync") public Object sync(@PathVariable String id, HttpServletRequest req) {
    auth.actor(req).require("business.write"); return service.sync(id);
  }
}
