package com.emie.crm.controller;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.JushuitanService;
import com.emie.crm.service.JushuitanCatalogJob;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jst")
public class JushuitanController {
  private final AuthService auth;
  private final JushuitanService service;
  private final JushuitanCatalogJob catalog;
  public JushuitanController(AuthService auth, JushuitanService service, JushuitanCatalogJob catalog) { this.auth = auth; this.service = service; this.catalog = catalog; }
  @GetMapping("/products/catalog") public Object catalog(HttpServletRequest req) { auth.actor(req).require("business.read"); return catalog.status(); }
  @PostMapping("/products/catalog/start") public Object startCatalog(@RequestBody JsonNode body,HttpServletRequest req) { return catalog.start(body,auth.actor(req)); }
  @PostMapping("/products/catalog/control") public Object controlCatalog(@RequestBody JsonNode body,HttpServletRequest req) { return catalog.control(body.path("action").asText(),auth.actor(req)); }
  @GetMapping("/config") public Object config(HttpServletRequest req) { auth.actor(req).require("business.read"); return service.config(); }
  @GetMapping("/shipments") public Object list(HttpServletRequest req) { auth.actor(req).require("business.read"); return service.list(); }
  @GetMapping("/shops") public Object shops(HttpServletRequest req) { auth.actor(req).require("order.approve"); return service.shops(); }
  @GetMapping("/settlements/shops")
  public Object settlementShops(HttpServletRequest req) {
    var actor = auth.actor(req);
    actor.require("business.read");
    actor.require("business.write");
    return service.shops(false);
  }
  @GetMapping("/warehouses") public Object warehouses(HttpServletRequest req) { auth.actor(req).require("business.read"); return service.warehouses(); }
  @PostMapping("/products/sync") public Object syncProducts(@RequestBody JsonNode body, HttpServletRequest req) { return service.syncProducts(auth.actor(req), body); }
  @PostMapping("/inventory") public Object inventory(@RequestBody JsonNode body, HttpServletRequest req) {
    auth.actor(req).require("business.read");
    var warehouse = body.path("warehouseId");
    require(warehouse.isIntegralNumber()
        && warehouse.canConvertToLong() && warehouse.asLong() > 0, "请选择有效的发货仓库编号");
    return service.inventory(body.path("skuId").asText(), warehouse.asLong());
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
  @GetMapping("/orders/{id}/external/history") public Object externalHistory(@PathVariable String id,HttpServletRequest req) { return service.externalHistory(id,auth.actor(req)); }
  @PostMapping("/orders/{id}/external/confirm") public Object confirmExternal(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
    return service.confirmExternal(id,body,auth.actor(req));
  }
  @PostMapping("/orders/{id}/sync") public Object sync(@PathVariable String id, HttpServletRequest req) {
    auth.actor(req).require("business.write"); return service.sync(id);
  }
}
