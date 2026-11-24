package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.JushuitanCreditPushService;
import com.emie.crm.service.JushuitanService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jst")
public class JushuitanCreditPushController {
  private final AuthService auth;
  private final JushuitanService jst;
  private final JushuitanCreditPushService queue;
  private final JushuitanCreditPushService.Transport transport;
  private final String accountingMode;

  public JushuitanCreditPushController(AuthService auth, JushuitanService jst,
      JushuitanCreditPushService queue, JushuitanCreditPushService.Transport transport,
      @Value("${CRM_JST_CREDIT_ACCOUNTING_MODE:UNCONFIGURED}") String accountingMode) {
    this.auth = auth; this.jst = jst; this.queue = queue; this.transport = transport;
    this.accountingMode = accountingMode;
  }

  @GetMapping("/credit-shipping-options")
  public Object options(HttpServletRequest request) {
    auth.actor(request).require("business.read");
    boolean enabled = "ON_ACCOUNT_TENDER".equals(accountingMode) && transport.ready();
    var result = new LinkedHashMap<String, Object>();
    result.put("enabled", enabled); result.put("mode", "CREDIT");
    result.put("accountingMode", "ON_ACCOUNT_TENDER".equals(accountingMode) ? accountingMode : "UNCONFIGURED");
    result.put("creditTermDescription", "ERP以全额账期挂账（非实收）放行，付款状态显示已付；CRM实收和应收按实际收款核算。");
    result.put("shops", enabled ? jst.shops() : java.util.List.of());
    result.put("warehouses", enabled ? jst.warehouses() : java.util.List.of());
    if (!enabled) result.put("message", transport.unavailableMessage());
    return result;
  }

  @GetMapping("/credit-pushes")
  public Object list(HttpServletRequest request) { return queue.list(auth.actor(request)); }

  @PostMapping("/orders/{id}/credit-push")
  public Object prepare(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    return queue.prepare(id, body, auth.actor(request));
  }

  @PostMapping("/orders/{id}/credit-push/retry")
  public Object retry(@PathVariable String id, HttpServletRequest request) { return queue.retry(id, auth.actor(request)); }
}
