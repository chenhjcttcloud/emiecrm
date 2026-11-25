package com.emie.crm.controller;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.OrderNumberService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/number-reservations")
public class OrderNumberController {
  private final AuthService auth;
  private final OrderNumberService numbers;

  public OrderNumberController(AuthService auth, OrderNumberService numbers) {
    this.auth = auth; this.numbers = numbers;
  }

  @PostMapping
  public Object reserve(@RequestBody JsonNode body, HttpServletRequest request) {
    var actor = auth.actor(request);
    actor.require("business.read"); actor.require("business.write");
    require(body.isObject() && body.path("requestId").isTextual(), "订单编号请求须为文本");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(numbers.reserve(body.path("requestId").asText(), actor));
  }
}
