package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.CustomerImportService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers/import")
public class CustomerImportController {
  private final AuthService auth;
  private final CustomerImportService service;

  public CustomerImportController(AuthService auth, CustomerImportService service) {
    this.auth = auth; this.service = service;
  }

  @PostMapping(value = "/parse", consumes = "application/octet-stream")
  public Object parse(@RequestParam String filename, @RequestBody byte[] file, HttpServletRequest request) {
    return service.parse(filename, file, auth.actor(request));
  }

  @PostMapping("/preview")
  public Object preview(@RequestBody JsonNode body, HttpServletRequest request) {
    return service.preview(body, auth.actor(request));
  }

  @PostMapping
  public Object commit(@RequestBody JsonNode body, HttpServletRequest request) {
    return service.commit(body, auth.actor(request));
  }
}
