package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.FeishuReceiptApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/feishu/receipt-approvals")
public class FeishuReceiptApprovalController {
  private final AuthService auth;
  private final FeishuReceiptApprovalService approvals;
  public FeishuReceiptApprovalController(AuthService auth, FeishuReceiptApprovalService approvals) {
    this.auth = auth; this.approvals = approvals;
  }
  @GetMapping("/config") public Object config(HttpServletRequest request) { return approvals.config(auth.actor(request)); }
  @GetMapping public Object list(HttpServletRequest request) { return approvals.list(auth.actor(request)); }
  @PostMapping public Object create(@RequestBody JsonNode body, HttpServletRequest request) { return approvals.create(body, auth.actor(request)); }
  @PostMapping("/{id}/sync") public Object sync(@PathVariable String id, HttpServletRequest request) { return approvals.sync(id, auth.actor(request)); }
  @PostMapping("/{id}/retry") public Object retry(@PathVariable String id, HttpServletRequest request) { return approvals.retry(id, auth.actor(request)); }
  @PostMapping("/{id}/cancel") public Object cancel(@PathVariable String id, HttpServletRequest request) { return approvals.cancel(id, auth.actor(request)); }
  @GetMapping("/{id}/flow") public Object flow(@PathVariable String id, HttpServletRequest request) { return approvals.flow(id, auth.actor(request)); }
}
