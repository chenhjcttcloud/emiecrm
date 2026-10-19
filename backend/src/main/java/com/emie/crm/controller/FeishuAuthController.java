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
    try {
      var session=req.getSession(false);
      if(session==null)throw new ApiException("登录请求已失效，请重新发起登录");
      synchronized(session) {
        Object expected=session.getAttribute("feishu.state"),expires=session.getAttribute("feishu.state.expires");
        session.removeAttribute("feishu.state");session.removeAttribute("feishu.state.expires");
        if(expected==null || !expected.equals(state) || !(expires instanceof Long time) || time<System.currentTimeMillis())throw new ApiException("登录请求已失效，请重新发起登录");
      }
      String name=identities.resolve(client.appId(),client.identity(code));
      req.changeSessionId();
      String ticket=UUID.randomUUID().toString();
      session.setAttribute("feishu.ticket",ticket);session.setAttribute("feishu.user",name);session.setAttribute("feishu.ticket.expires",System.currentTimeMillis()+60_000L);
      return redirect("/?sso_ticket="+ticket);
    } catch(ApiException e) {return redirect("/?sso_error="+encode(e.getMessage()));}
    catch(Exception e) {return redirect("/?sso_error="+encode("飞书登录失败，请重新登录"));}
  }
  @PostMapping("/exchange")
  public Object exchange(@RequestBody Map<String,String> body,HttpServletRequest req) {
    HttpSession session=req.getSession(false);
    if(session==null)throw new ApiException(401,"登录票据已失效");
    synchronized(session) {
      Object expected=session.getAttribute("feishu.ticket"),name=session.getAttribute("feishu.user"),expires=session.getAttribute("feishu.ticket.expires");
      session.removeAttribute("feishu.ticket");session.removeAttribute("feishu.user");session.removeAttribute("feishu.ticket.expires");
      if(expected==null || !expected.equals(body.get("ticket")) || name==null || !(expires instanceof Long time) || time<System.currentTimeMillis())throw new ApiException(401,"登录票据已失效");
      return auth.createSession(name.toString());
    }
  }
  private ResponseEntity<Void> redirect(String url) {return ResponseEntity.status(302).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").location(URI.create(url)).build();}
}
