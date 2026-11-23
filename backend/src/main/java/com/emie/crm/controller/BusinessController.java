package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.AuthService;
import com.emie.crm.service.BusinessService;
import com.emie.crm.service.JushuitanSettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BusinessController {
  private final AuthService auth;
  private final BusinessService service;
  private final JushuitanSettlementService settlements;
  private final CrmRepository db;

  public BusinessController(AuthService auth, BusinessService service, JushuitanSettlementService settlements,
                            CrmRepository db) {
    this.auth = auth;
    this.service = service;
    this.settlements = settlements;
    this.db = db;
  }

  @GetMapping("/state")
  public ResponseEntity<JsonNode> state(HttpServletRequest req,
      @RequestParam(name = "knownRevision", required = false) String knownRevision) {
    auth.actor(req).require("business.read");
    Long revision = null;
    if (knownRevision != null) {
      if (!knownRevision.matches("[0-9]{1,19}")) throw new ApiException("业务数据版本无效");
      try { revision = Long.parseLong(knownRevision); }
      catch (NumberFormatException e) { throw new ApiException("业务数据版本无效"); }
    }
    return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(service.state(revision));
  }

  @PostMapping("/state")
  public JsonNode save(@RequestBody JsonNode body, HttpServletRequest req) {
    return service.save(body, auth.actor(req));
  }

  @PostMapping("/orders/{id}/delete")
  public JsonNode deleteOrder(@PathVariable String id, HttpServletRequest req) {
    return service.deleteOrder(id, auth.actor(req));
  }

  @PostMapping("/jst/settlements/candidates")
  public Object settlementCandidates(@RequestBody JsonNode body, HttpServletRequest req) {
    return settlements.candidates(body, auth.actor(req));
  }

  @PostMapping("/jst/settlements/preview")
  public JushuitanSettlementService.Preview previewSettlement(@RequestBody JsonNode body, HttpServletRequest req) {
    return settlements.preview(body, auth.actor(req));
  }

  @PostMapping("/jst/settlements")
  public JsonNode createSettlement(@RequestBody JsonNode body, HttpServletRequest req) {
    return settlements.create(body, auth.actor(req));
  }

  @GetMapping("/orders/{id}/history")
  public Object orderHistory(@PathVariable String id, HttpServletRequest req) {
    return service.orderHistory(id, auth.actor(req));
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
