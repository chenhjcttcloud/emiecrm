package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemService implements ApplicationRunner {
  public static final Map<String, String> PERMISSIONS;

  static {
    var p = new LinkedHashMap<String, String>();
    p.put("business.read", "查看业务数据");
    p.put("business.write", "维护业务数据");
    p.put("order.approve", "批准订单");
    p.put("payment.write", "登记收款与核销");
    p.put("system.manage", "管理系统设置");
    p.put("data.backup", "备份与恢复业务数据");
    PERMISSIONS = Collections.unmodifiableMap(p);
  }

  @Value("${CRM_FEISHU_APP_ID:}")
  private String feishuAppId;

  private final CrmRepository db;
  private final PasswordCodec passwords;

  public SystemService(CrmRepository db, PasswordCodec passwords) {
    this.db = db;
    this.passwords = passwords;
  }

  @Override
  public void run(ApplicationArguments args) {
    require(
        !"1".equals(System.getenv("CRM_TEST_MODE"))
            || System.getenv().getOrDefault("CRM_DB_NAME", "").endsWith("_test"),
        "测试模式只能连接_test数据库");
    seed("管理员", "系统和业务的完整管理权限", new ArrayList<>(PERMISSIONS.keySet()));
    seed("财务", "维护业务数据，登记收款与核销", List.of("business.read", "business.write", "payment.write"));
    seed("待授权", "飞书新成员，等待管理员分配角色", List.of());
    seed("销售", "维护客户、产品及订单", List.of("business.read", "business.write"));
  }

  private void seed(String name, String desc, List<String> ps) {
    db.jdbc()
        .update(
            "INSERT IGNORE INTO roles(name,description,permissions,builtin) VALUES(?,?,?,TRUE)",
            name,
            desc,
            db.encode(ps));
  }

  public long accountCount() {
    return db.jdbc().queryForObject("SELECT count(*) FROM users", Long.class);
  }

  private String text(JsonNode n, String key) {
    return n.path(key).asText("");
  }

  private String nullable(JsonNode n, String key) {
    String value = text(n, key);
    return value.isBlank() ? null : value;
  }

  private void credentials(String name, String password, String role, boolean update) {
    require(password.length() >= 8, "密码至少8位");
    String salt = UUID.randomUUID().toString(), hash = passwords.hash(password, salt);
    if (update)
      db.jdbc()
          .update("UPDATE users SET salt=?,password=?,role=? WHERE name=?", salt, hash, role, name);
    else
      db.jdbc()
          .update(
              "INSERT INTO users(name,salt,password,role) VALUES(?,?,?,?)", name, salt, hash, role);
  }

  @Transactional
  public void setup(JsonNode body) {
    db.state(true);
    require(accountCount() == 0, "已完成初始化");
    require(!text(body, "name").isBlank(), "请填写账户");
    credentials(text(body, "name"), text(body, "password"), "管理员", false);
  }

  @Transactional
  public void legacyCreate(JsonNode body, Actor actor) {
    require(
        List.of("管理员", "财务", "销售").contains(text(body, "role")) && !text(body, "name").isBlank(),
        "账户或角色无效");
    credentials(text(body, "name"), text(body, "password"), text(body, "role"), false);
    db.audit(actor.name(), "创建账户 " + text(body, "name"));
  }

  public List<Map<String, Object>> legacyUsers() {
    return db.rows("SELECT name,role FROM users");
  }

  public List<Map<String, Object>> users() {
    return db.rows(
        "SELECT u.name,u.role,COALESCE(p.display_name,u.name) AS"
            + " displayName,COALESCE(p.status,'启用') AS status,COALESCE(p.email,'') AS"
            + " email,COALESCE(p.phone,'') AS phone,p.department_id AS departmentId,d.name AS"
            + " department,p.created_at AS createdAt,COALESCE(f.open_id,'') AS openId,COALESCE(f.feishu_user_id,'') AS feishuUserId FROM users u LEFT JOIN user_profiles p ON"
            + " p.name=u.name LEFT JOIN departments d ON d.id=p.department_id LEFT JOIN feishu_identities f ON f.user_name=u.name AND f.app_id=? ORDER BY p.created_at"
            + " DESC,u.name", feishuAppId);
  }

  @Transactional
  public void saveUser(JsonNode b, Actor actor) {
    String name = text(b, "name").trim(),
        display = text(b, "displayName").trim(),
        role = text(b, "role"),
        status = text(b, "status").equals("停用") ? "停用" : "启用",
        department = nullable(b, "departmentId");
    require(
        name.matches("[\\w\\p{IsHan}.@-]{2,50}") && !display.isBlank() && display.length() <= 100,
        "请填写有效账户和姓名");
    db.state(true);
    var all =
        db.rows(
            "SELECT u.name,u.role,COALESCE(p.status,'启用') AS status FROM users u LEFT JOIN"
                + " user_profiles p ON p.name=u.name FOR UPDATE");
    var old = all.stream().filter(u -> name.equals(u.get("name"))).findFirst();
    boolean create = text(b, "action").equals("create");
    require(create ? old.isEmpty() : old.isPresent(), create ? "账户已存在" : "账户不存在");
    require(!db.rows("SELECT name FROM roles WHERE name=?", role).isEmpty(), "角色不存在");
    if (old.isPresent()
        && "管理员".equals(old.get().get("role"))
        && "启用".equals(old.get().get("status"))
        && (!role.equals("管理员") || status.equals("停用")))
      require(
          all.stream()
                  .filter(u -> "管理员".equals(u.get("role")) && "启用".equals(u.get("status")))
                  .count()
              > 1,
          "至少保留一个启用的管理员");
    if (department != null)
      require(
          !db.rows("SELECT id FROM departments WHERE id=? AND status='启用'", department).isEmpty(),
          "请选择启用的部门");
    require(text(b, "email").length() <= 191 && text(b, "phone").length() <= 32, "联系方式过长");
    if (create || !text(b, "password").isEmpty())
      credentials(name, text(b, "password"), role, !create);
    else db.jdbc().update("UPDATE users SET role=? WHERE name=?", role, name);
    db.jdbc()
        .update(
            "INSERT INTO user_profiles(name,display_name,email,phone,department_id,status)"
                + " VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                + " display_name=VALUES(display_name),email=VALUES(email),phone=VALUES(phone),department_id=VALUES(department_id),status=VALUES(status)",
            name,
            display,
            text(b, "email"),
            text(b, "phone"),
            department,
            status);
    db.audit(actor.name(), (create ? "创建用户 " : "编辑用户 ") + name);
  }

  public List<Map<String, Object>> roles() {
    var rows =
        db.rows(
            "SELECT r.*, (SELECT COUNT(*) FROM users u WHERE u.role=r.name) AS members FROM roles r"
                + " ORDER BY builtin DESC,name");
    rows.forEach(r -> r.put("permissions", db.parse(r.get("permissions").toString())));
    return rows;
  }

  @Transactional
  public void saveRole(JsonNode b, Actor actor) {
    String name = text(b, "name").trim(), desc = text(b, "description");
    require(!name.isEmpty() && name.length() <= 30 && desc.length() <= 255, "角色名称1–30字，描述不超过255字");
    var ps = b.path("permissions");
    require(ps.isArray(), "权限设置无效");
    var selected = new LinkedHashSet<String>();
    ps.forEach(
        p -> {
          require(PERMISSIONS.containsKey(p.asText()), "权限设置无效");
          selected.add(p.asText());
        });
    db.state(true);
    var rows = db.rows("SELECT name FROM roles WHERE name=? FOR UPDATE", name);
    boolean create = text(b, "action").equals("create");
    require(create ? rows.isEmpty() : !rows.isEmpty(), create ? "角色已存在" : "角色不存在");
    require(!name.equals("管理员"), "管理员角色的完整权限不可修改");
    db.jdbc()
        .update(
            "INSERT INTO roles(name,description,permissions,builtin) VALUES(?,?,?,FALSE) ON"
                + " DUPLICATE KEY UPDATE"
                + " description=VALUES(description),permissions=VALUES(permissions)",
            name,
            desc,
            db.encode(selected));
    db.audit(actor.name(), "保存角色 " + name);
  }

  public List<Map<String, Object>> departments() {
    var rows =
        db.rows(
            "SELECT d.*,parent.name AS parentName,(SELECT COUNT(*) FROM user_profiles p WHERE"
                + " p.department_id=d.id) AS members FROM departments d LEFT JOIN departments"
                + " parent ON parent.id=d.parent_id ORDER BY d.created_at,d.name");
    rows.forEach(d -> d.put("parentId", d.get("parent_id")));
    return rows;
  }

  @Transactional
  public void saveDepartment(JsonNode b, Actor actor) {
    db.state(true);
    String id = text(b, "id").isBlank() ? UUID.randomUUID().toString() : text(b, "id"),
        name = text(b, "name").trim(),
        manager = text(b, "manager"),
        parent = nullable(b, "parentId"),
        status = text(b, "status").equals("停用") ? "停用" : "启用";
    require(
        !name.isEmpty() && name.length() <= 100 && manager.length() <= 100,
        "请填写部门名称，名称及负责人不超过100字");
    var all = db.rows("SELECT * FROM departments");
    if (!text(b, "id").isBlank())
      require(all.stream().anyMatch(d -> id.equals(d.get("id"))), "部门不存在");
    require(
        all.stream()
            .noneMatch(
                d ->
                    !id.equals(d.get("id"))
                        && name.equals(d.get("name"))
                        && Objects.equals(parent, d.get("parent_id"))),
        "同级部门名称不能重复");
    if (parent != null) {
      var seen = new HashSet<String>();
      seen.add(id);
      String current = parent;
      boolean first = true;
      while (current != null) {
        String cursor = current;
        var d = all.stream().filter(x -> cursor.equals(x.get("id"))).findFirst();
        require(d.isPresent() && (!first || "启用".equals(d.get().get("status"))), "请选择启用的上级部门");
        require(seen.add(current), "上级部门不能选择自身或下级部门");
        current = (String) d.get().get("parent_id");
        first = false;
      }
    }
    if (status.equals("停用"))
      require(
          db.rows(
                      "SELECT name FROM user_profiles WHERE department_id=? AND status='启用' LIMIT"
                          + " 1",
                      id)
                  .isEmpty()
              && all.stream()
                  .noneMatch(d -> id.equals(d.get("parent_id")) && "启用".equals(d.get("status"))),
          "部门仍有启用用户或下级部门，请先调整");
    db.jdbc()
        .update(
            "INSERT INTO departments(id,name,parent_id,manager,status) VALUES(?,?,?,?,?) ON"
                + " DUPLICATE KEY UPDATE"
                + " name=VALUES(name),parent_id=VALUES(parent_id),manager=VALUES(manager),status=VALUES(status)",
            id,
            name,
            parent,
            manager,
            status);
    db.audit(actor.name(), "保存部门 " + name);
  }

  public List<JsonNode> audit() {
    return db.rows("SELECT id,payload FROM audit ORDER BY seq DESC LIMIT 500").stream()
        .map(
            r -> {
              var node =
                  (com.fasterxml.jackson.databind.node.ObjectNode)
                      db.parse(r.get("payload").toString());
              node.put("id", r.get("id").toString());
              return (JsonNode) node;
            })
        .toList();
  }
}
