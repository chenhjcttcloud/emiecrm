package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.JushuitanDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jst/orders/{id}/documents")
public class JushuitanDocumentController {
  private final AuthService auth;
  private final JushuitanDocumentService documents;

  public JushuitanDocumentController(AuthService auth, JushuitanDocumentService documents) {
    this.auth = auth;
    this.documents = documents;
  }

  @GetMapping
  public JushuitanDocumentService.Documents get(@PathVariable String id, HttpServletRequest req) {
    return documents.get(id, auth.actor(req));
  }

  @PostMapping("/sale")
  public Map<String, Boolean> sale(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    documents.linkSale(id, body, auth.actor(req));
    return Map.of("ok", true);
  }

  @PostMapping("/sales")
  public Map<String, Boolean> sales(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    documents.replaceSettlementSales(id, body, auth.actor(req));
    return Map.of("ok", true);
  }

  @PostMapping("/purchases")
  public Map<String, Boolean> purchase(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    documents.linkPurchase(id, body, auth.actor(req));
    return Map.of("ok", true);
  }
}
