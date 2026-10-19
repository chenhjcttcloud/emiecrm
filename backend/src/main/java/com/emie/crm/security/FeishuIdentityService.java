package com.emie.crm.security;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.lark.oapi.service.authen.v1.model.GetUserInfoRespBody;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeishuIdentityService {
  private final CrmRepository db;
  private final PasswordCodec passwords;
  public FeishuIdentityService(CrmRepository db, PasswordCodec passwords) { this.db=db; this.passwords=passwords; }

  @Transactional
  public String resolve(String appId, GetUserInfoRespBody info) {
    if(info==null || info.getOpenId()==null || info.getOpenId().isBlank()) throw new ApiException("飞书未返回用户身份");
    db.state(true);
    var administrators=db.rows("SELECT u.name FROM users u LEFT JOIN user_profiles p ON p.name=u.name WHERE u.role='管理员' AND COALESCE(p.status,'启用')='启用'");
    if(administrators.isEmpty())throw new ApiException("请先初始化管理员账号，再使用飞书登录");
    var rows=db.rows("SELECT user_name FROM feishu_identities WHERE app_id=? AND open_id=?",appId,info.getOpenId());
    String name;
    if(rows.isEmpty()) {
      try { name="fs_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((appId+":"+info.getOpenId()).getBytes(StandardCharsets.UTF_8))).substring(0,32); }
      catch(Exception e) { throw new IllegalStateException(e); }
      String salt=UUID.randomUUID().toString();
      db.jdbc().update("INSERT INTO users(name,salt,password,role) VALUES(?,?,?,?)",name,salt,passwords.hash(UUID.randomUUID().toString(),salt),"待授权");
      String display=info.getName()==null || info.getName().isBlank()?"飞书用户":info.getName();
      if(display.length()>100)display=display.substring(0,100);
      db.jdbc().update("INSERT INTO user_profiles(name,display_name,status) VALUES(?,?,'启用')",name,display);
      db.jdbc().update("INSERT INTO feishu_identities(app_id,open_id,user_name,feishu_user_id) VALUES(?,?,?,?)",appId,info.getOpenId(),name,info.getUserId()==null?"":info.getUserId());
      db.audit(name,"飞书首次登录，等待管理员授权");
    } else {
      name=rows.getFirst().get("user_name").toString();
      db.jdbc().update("UPDATE feishu_identities SET feishu_user_id=? WHERE app_id=? AND open_id=?",info.getUserId()==null?"":info.getUserId(),appId,info.getOpenId());
    }
    var users=db.rows("SELECT u.name,COALESCE(p.status,'启用') AS status FROM users u LEFT JOIN user_profiles p ON p.name=u.name WHERE u.name=?",name);
    if(users.isEmpty() || "停用".equals(users.getFirst().get("status"))) throw new ApiException(403,"账户已停用，请联系管理员");
    return name;
  }
}
