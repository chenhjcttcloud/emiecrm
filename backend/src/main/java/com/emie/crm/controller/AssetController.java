package com.emie.crm.controller;

import com.emie.crm.security.AuthService;
import com.emie.crm.service.AssetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
  private final AuthService auth;
  private final AssetService assets;
  public AssetController(AuthService auth, AssetService assets) { this.auth = auth; this.assets = assets; }

  @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public Object upload(@RequestBody byte[] bytes, HttpServletRequest request) {
    var actor = auth.actor(request);
    actor.require("business.read");
    actor.require("business.write");
    return java.util.Map.of("url", assets.store(bytes));
  }

  @GetMapping("/{id}")
  public ResponseEntity<byte[]> image(@PathVariable String id, HttpServletRequest request) {
    auth.actor(request).require("business.read");
    var asset = assets.read(id);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.contentType()))
        .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
        .body(asset.content());
  }
}
