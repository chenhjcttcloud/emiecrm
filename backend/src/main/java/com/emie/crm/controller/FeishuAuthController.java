package com.emie.crm.controller;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuAuthController {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FeishuAuthController.class);
  private final FeishuClient client;
  private final FeishuIdentityService identities;
  private final AuthService auth;
  private final FeishuLongConnection channel;
  public FeishuAuthController(FeishuClient client,FeishuIdentityService identities,AuthService auth,FeishuLongConnection channel) {this.client=client;this.identities=identities;this.auth=auth;this.channel=channel;}
  private String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
  @GetMapping("/config")
  public Object config() {return Map.of("enabled",client.ready(),"redirectUri",client.redirectUri(),"longConnection",channel.status());}
  @GetMapping("/start")
  public ResponseEntity<Void> start(HttpServletRequest req) {
    if(!client.ready())throw new ApiException("飞书登录尚未配置完成");
    var session=req.getSession(true);
    String state=UUID.randomUUID().toString();
    session.setAttribute("feishu.state",state);session.setAttribute("feishu.state.expires",System.currentTimeMillis()+5*60_000L);
    session.removeAttribute("feishu.ticket");session.removeAttribute("feishu.user");
    return redirect("https://open.feishu.cn/open-apis/authen/v1/index?app_id="+encode(client.appId())+"&redirect_uri="+encode(client.redirectUri())+"&state="+state);
  }
  @GetMapping("/callback")
  public ResponseEntity<Void> callback(@RequestParam(defaultValue="") String code,@RequestParam(defaultValue="") String state,HttpServletRequest req) {
    String stage="browser-session";
    try {
      var session=req.getSession(false);
      if(session==null)throw new ApiException("登录请求已失效，请重新发起登录");
      stage="validate-state";
      synchronized(session) {
        Object expected=session.getAttribute("feishu.state"),expires=session.getAttribute("feishu.state.expires");
        if (!(expires instanceof Long time) || time<System.currentTimeMillis()) {
          stage="state-expired";
          session.removeAttribute("feishu.state");session.removeAttribute("feishu.state.expires");
          throw new ApiException("登录请求已失效，请重新发起登录");
        }
        if(expected==null || !expected.equals(state)) {
          stage="state-mismatch";
          throw new ApiException("登录请求已失效，请重新发起登录");
        }
        session.removeAttribute("feishu.state");session.removeAttribute("feishu.state.expires");
      }
      stage="exchange-code";
      var identity=client.identity(code);
      stage="resolve-user";
      String name=identities.resolve(client.appId(),identity);
      req.changeSessionId();
      String ticket=UUID.randomUUID().toString();
      session.setAttribute("feishu.ticket",ticket);session.setAttribute("feishu.user",name);session.setAttribute("feishu.ticket.expires",System.currentTimeMillis()+60_000L);
      return redirect("/?sso_ticket="+ticket);
    } catch(ApiException e) {
      log.warn("Feishu login rejected at {}: {}",stage,e.getMessage());
      return redirect("/?sso_error="+encode(e.getMessage()));
    } catch(Exception e) {
      // Record only the stage and exception type; never log code/state/tickets or SDK response bodies.
      log.warn("Feishu login failed at {} ({})",stage,e.getClass().getSimpleName());
      return redirect("/?sso_error="+encode("飞书登录失败，请重新登录"));
    }
  }
  @PostMapping("/exchange")
  public Object exchange(@RequestBody Map<String,String> body,HttpServletRequest req) {
    HttpSession session=req.getSession(false);
    if(session==null){log.warn("Feishu ticket exchange rejected: browser-session-missing");throw new ApiException(401,"登录票据已失效");}
    synchronized(session) {
      Object expected=session.getAttribute("feishu.ticket"),name=session.getAttribute("feishu.user"),expires=session.getAttribute("feishu.ticket.expires");
      if (!(expires instanceof Long time) || time<System.currentTimeMillis()) {
        session.removeAttribute("feishu.ticket");session.removeAttribute("feishu.user");session.removeAttribute("feishu.ticket.expires");
        log.warn("Feishu ticket exchange rejected: ticket-expired-or-consumed");
        throw new ApiException(401,"登录票据已失效");
      }
      if(expected==null || !expected.equals(body.get("ticket")) || name==null){
        log.warn("Feishu ticket exchange rejected: ticket-mismatch");
        throw new ApiException(401,"登录票据已失效");
      }
      var result=auth.createSession(name.toString());
      session.removeAttribute("feishu.ticket");session.removeAttribute("feishu.user");session.removeAttribute("feishu.ticket.expires");
      return result;
    }
  }
  private ResponseEntity<Void> redirect(String url) {return ResponseEntity.status(302).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").location(URI.create(url)).build();}
}
