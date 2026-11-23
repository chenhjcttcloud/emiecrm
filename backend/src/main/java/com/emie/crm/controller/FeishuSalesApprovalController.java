package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.FeishuSalesApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/feishu/approvals")
public class FeishuSalesApprovalController {
  private final AuthService auth;
  private final FeishuSalesApprovalService approvals;

  public FeishuSalesApprovalController(AuthService auth, FeishuSalesApprovalService approvals) {
    this.auth = auth;
    this.approvals = approvals;
  }

  @GetMapping("/config")
  public Object config(HttpServletRequest request) {
    return approvals.config(auth.actor(request));
  }

  @GetMapping
  public Object list(HttpServletRequest request) {
    return approvals.list(auth.actor(request));
  }

  @GetMapping("/orders/{id}/flow")
  public Object flow(@PathVariable String id, HttpServletRequest request) {
    return approvals.flow(id, auth.actor(request));
  }

  @PostMapping("/orders/{id}/submit")
  public Object submit(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    return approvals.submit(id, body, auth.actor(request));
  }

  @PostMapping("/orders/{id}/create-and-submit")
  public Object createAndSubmit(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    return approvals.createAndSubmit(id, body, auth.actor(request));
  }

  @PostMapping("/orders/{id}/sync")
  public Object sync(@PathVariable String id, HttpServletRequest request) {
    return approvals.sync(id, auth.actor(request));
  }

  @PostMapping("/orders/{id}/cancel")
  public Object cancel(@PathVariable String id, HttpServletRequest request) {
    return approvals.cancel(id, auth.actor(request));
  }

}
