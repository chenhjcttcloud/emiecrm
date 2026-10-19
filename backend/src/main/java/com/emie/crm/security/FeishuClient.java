package com.emie.crm.security;

import com.emie.crm.common.exception.ApiException;
import com.lark.oapi.Client;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.service.authen.v1.model.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeishuClient {
  private final String appId, appSecret, redirectUri;
  private final boolean enabled;
  public FeishuClient(@Value("${CRM_FEISHU_APP_ID:}") String appId,
      @Value("${CRM_FEISHU_APP_SECRET:}") String appSecret,
      @Value("${CRM_FEISHU_REDIRECT_URI:}") String redirectUri,
      @Value("${CRM_FEISHU_ENABLED:0}") String enabled) {
    this.appId=appId; this.appSecret=appSecret; this.redirectUri=redirectUri; this.enabled="1".equals(enabled);
  }
  public boolean ready() { return enabled && !appId.isBlank() && !appSecret.isBlank() && redirectUri.matches("https?://[^?#]+/api/auth/feishu/callback"); }
  public String appId() { return appId; }
  public String redirectUri() { return redirectUri; }
  public com.lark.oapi.ws.Client longConnection(com.lark.oapi.event.EventDispatcher dispatcher, Runnable reconnecting, Runnable reconnected) {
    if(!ready())throw new ApiException("飞书应用凭据尚未配置完成");
    return new com.lark.oapi.ws.Client.Builder(appId,appSecret).eventHandler(dispatcher)
        .autoReconnect(true).onReconnecting(reconnecting).onReconnected(reconnected).build();
  }
  public GetUserInfoRespBody identity(String code) {
    if(!ready())throw new ApiException("飞书登录尚未配置完成");
    if(code==null || code.isBlank() || code.length()>2048) throw new ApiException("飞书授权码无效");
    try {
      var client=Client.newBuilder(appId,appSecret).logReqAtDebug(false).requestTimeout(10,TimeUnit.SECONDS).build();
      var token=client.authen().oidcAccessToken().create(CreateOidcAccessTokenReq.newBuilder()
          .createOidcAccessTokenReqBody(CreateOidcAccessTokenReqBody.newBuilder().grantType("authorization_code").code(code).build()).build());
      if(!token.success() || token.getData()==null)throw new ApiException("飞书授权失败，请重新登录");
      var info=client.authen().userInfo().get(RequestOptions.newBuilder().userAccessToken(token.getData().getAccessToken()).build());
      if(!info.success())throw new ApiException("无法获取飞书用户信息");
      return info.getData();
    } catch(ApiException e) { throw e; }
    catch(Exception e) { throw new ApiException("飞书登录服务暂不可用，请稍后重试"); }
  }
}
