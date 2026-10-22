package com.emie.crm.security;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final CrmRepository db;
  private final PasswordCodec passwords;
  private final boolean loginRequired;
  // Sessions remain valid until explicitly revoked or the service restarts.
  private record Session(String name) {}
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  public AuthService(
      CrmRepository db, PasswordCodec passwords, @Value("${CRM_REQUIRE_LOGIN:0}") String mode,
      @Value("${CRM_FEISHU_ENABLED:0}") String feishuEnabled) {
    this.db = db;
    this.passwords = passwords;
    this.loginRequired = "1".equals(mode) || "1".equals(feishuEnabled);
  }

  public boolean loginRequired() {
    return loginRequired;
  }

  public Set<String> permissions(String role) {
    var rows = db.rows("SELECT permissions FROM roles WHERE name=?", role);
    var result = new HashSet<String>();
    if (!rows.isEmpty())
      db.parse(rows.getFirst().get("permissions").toString()).forEach(p -> result.add(p.asText()));
    return result;
  }

  public Actor actor(HttpServletRequest req) {
    String token = req.getHeader("Authorization");
    token = token != null && token.startsWith("Bearer ") ? token.substring(7) : "";
    var session = sessions.get(token);
    String name = session == null ? null : session.name();
    if (name == null) {
      if (!loginRequired) return new Actor("本地工作台", "管理员", permissions("管理员"));
      throw new ApiException(401, "请先登录");
    }
    var rows =
        db.rows(
            "SELECT u.role,COALESCE(p.status,'启用') AS status FROM users u LEFT JOIN user_profiles p"
                + " ON p.name=u.name WHERE u.name=?",
            name);
    if (rows.isEmpty() || "停用".equals(rows.getFirst().get("status"))) {
      sessions.remove(token);
      throw new ApiException(401, "账户已停用或失效");
    }
    String role = rows.getFirst().get("role").toString();
    return new Actor(name, role, permissions(role));
  }

  public Map<String, Object> login(JsonNode body) {
    var rows =
        db.rows(
            "SELECT u.*,COALESCE(p.status,'启用') AS status FROM users u LEFT JOIN user_profiles p ON"
                + " p.name=u.name WHERE u.name=?",
            body.path("name").asText());
    if (rows.isEmpty()) throw new ApiException("账户或密码不正确");
    var row = rows.getFirst();
    if ("停用".equals(row.get("status"))) throw new ApiException("账户已停用");
    if (!passwords.matches(
        body.path("password").asText(), row.get("salt").toString(), row.get("password").toString()))
      throw new ApiException("账户或密码不正确");
    return createSession(row.get("name").toString());
  }

  public Map<String, Object> currentUser(HttpServletRequest req) {
    var actor = actor(req);
    var rows = db.rows("SELECT display_name FROM user_profiles WHERE name=?", actor.name());
    return Map.of("name", actor.name(), "displayName", rows.isEmpty() ? actor.name() : rows.getFirst().get("display_name"),
        "role", actor.role(), "permissions", actor.permissions(), "pending", "待授权".equals(actor.role()));
  }

  public Map<String, Object> createSession(String name) {
    String token = UUID.randomUUID().toString();
    sessions.put(token, new Session(name));
    var row=db.rows("SELECT role FROM users WHERE name=?", name);
    return Map.of("token", token, "user", Map.of("name",name,"role",row.isEmpty()?"":row.getFirst().get("role")));
  }

  public void revokeUser(String name) {
    sessions.entrySet().removeIf(e -> e.getValue().name().equals(name));
  }

  public void logout(HttpServletRequest req) {
    String token = req.getHeader("Authorization");
    if (token != null) sessions.remove(token.replaceFirst("^Bearer ", ""));
  }
}
