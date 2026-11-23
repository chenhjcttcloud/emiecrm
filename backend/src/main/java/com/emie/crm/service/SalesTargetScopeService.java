package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Shared current-department scope. It never deletes personnel targets or changes actual sales. */
@Service
public class SalesTargetScopeService {
  static final String SELECT = "SELECT mode,department_ids,revision,updated_at,updated_by FROM sales_target_scope WHERE id=1";
  static final String ACCOUNTS = "SELECT COALESCE(p.display_name,u.name) AS owner,d.id AS departmentId,d.name AS department FROM users u LEFT JOIN user_profiles p ON p.name=u.name LEFT JOIN departments d ON d.id=p.department_id";
  private static final String DEPARTMENTS = "SELECT id,name FROM departments ORDER BY name,id";
  private static final String ENSURE = "INSERT INTO sales_target_scope(id,mode,department_ids,revision,updated_at,updated_by) VALUES(1,'all',JSON_ARRAY(),0,NULL,NULL) ON DUPLICATE KEY UPDATE id=id";
  private final CrmRepository db;

  public SalesTargetScopeService(CrmRepository db) { this.db = db; }

  private record Configuration(String mode, List<String> departmentIds, long revision, Object updatedAt, Object updatedBy) {
    Map<String, Object> metadata() {
      var result = new LinkedHashMap<String, Object>();
      result.put("mode", mode); result.put("departmentIds", departmentIds); result.put("revision", revision);
      result.put("updatedAt", updatedAt); result.put("updatedBy", updatedBy);
      return result;
    }
  }

  private record Department(String id, String name, String status) {
    Map<String, Object> metadata() {
      var result = new LinkedHashMap<String, Object>();
      result.put("departmentId", id); result.put("department", name); result.put("departmentStatus", status);
      return result;
    }
  }

  static final class Snapshot {
    private final Configuration config;
    private final Map<String, Department> owners;
    private final List<Map<String, String>> departments;
    private final Set<String> selected;

    private Snapshot(Configuration config, Map<String, Department> owners, List<Map<String, String>> departments) {
      this.config = config; this.owners = Map.copyOf(owners); this.departments = List.copyOf(departments);
      var valid = new HashSet<String>(); departments.forEach(d -> valid.add(d.get("id")));
      valid.retainAll(config.departmentIds());
      this.selected = Set.copyOf(valid);
    }

    boolean selected() { return config.mode().equals("selected"); }
    boolean includes(String owner) {
      if (!selected()) return true;
      var department = owners.get(owner);
      return department != null && department.status().equals("assigned") && selected.contains(department.id());
    }
    Map<String, Object> metadata() { return config.metadata(); }
    Map<String, Object> department(String owner) {
      return owners.getOrDefault(owner, new Department(null, null, "unmatched")).metadata();
    }
    List<Map<String, String>> selectableDepartments() {
      return departments.stream().filter(d -> !selected() || selected.contains(d.get("id"))).toList();
    }
  }

  private Configuration configuration(String lock) {
    var rows = db.rows(SELECT + lock);
    if (rows.isEmpty()) return new Configuration("all", List.of(), 0, null, null);
    var row = rows.getFirst();
    String mode = Objects.toString(row.get("mode"), "");
    var ids = db.parse(Objects.toString(row.get("department_ids"), "[]"));
    require(Set.of("all", "selected").contains(mode) && ids.isArray(), "部门统计范围配置无效，请联系管理员");
    var selected = new LinkedHashSet<String>();
    for (var id : ids) {
      require(id.isTextual() && !id.asText().isBlank() && id.asText().length() <= 64, "部门统计范围配置无效，请联系管理员");
      selected.add(id.asText());
    }
    return new Configuration(mode, List.copyOf(selected), ((Number)row.get("revision")).longValue(), row.get("updated_at"), row.get("updated_by"));
  }

  private List<Map<String, String>> departments() {
    return db.rows(DEPARTMENTS).stream()
        .map(row -> Map.of("id", Objects.toString(row.get("id"), ""), "name", Objects.toString(row.get("name"), "")))
        .filter(row -> !row.get("id").isBlank()).toList();
  }

  private Snapshot snapshot(Configuration config) {
    var owners = new HashMap<String, Department>();
    for (var account : db.rows(ACCOUNTS)) {
      String owner = Objects.toString(account.get("owner"), "");
      if (owner.isBlank()) continue;
      if (owners.containsKey(owner)) {
        owners.put(owner, new Department(null, null, "ambiguous"));
        continue;
      }
      String id = Objects.toString(account.get("departmentId"), ""), name = Objects.toString(account.get("department"), "");
      owners.put(owner, id.isBlank() || name.isBlank() ? new Department(null, null, "unassigned") : new Department(id, name, "assigned"));
    }
    return new Snapshot(config, owners, departments());
  }

  Snapshot snapshot() { return snapshot(configuration("")); }

  // Called inside an authorized target-write transaction. The no-op upsert serializes even first writes,
  // without replacing an existing selection or incrementing its revision, then retains its scope lock.
  Snapshot snapshotForWrite() {
    db.jdbc().update(ENSURE);
    return snapshot(configuration(" FOR SHARE"));
  }

  private Map<String, Object> adminView(Configuration config, List<Map<String, String>> departments) {
    var result = config.metadata();
    var existing = new HashSet<String>(); departments.forEach(d -> existing.add(d.get("id")));
    result.put("departments", departments);
    result.put("missingDepartmentIds", config.departmentIds().stream().filter(id -> !existing.contains(id)).toList());
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> view(Actor actor) {
    actor.require("system.manage");
    return adminView(configuration(""), departments());
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> save(JsonNode body, Actor actor) {
    actor.require("system.manage");
    String mode = body.path("mode").asText();
    require(Set.of("all", "selected").contains(mode), "请选择有效部门范围模式");
    var requested = body.path("departmentIds");
    require(requested.isArray() && requested.size() <= 1000, "部门列表无效或超过1000项");
    var selected = new LinkedHashSet<String>();
    for (var id : requested) {
      require(id.isTextual() && !id.asText().isBlank() && id.asText().length() <= 64, "请选择有效部门，部门标识最长64字");
      require(selected.add(id.asText()), "部门不能重复");
    }
    require(!mode.equals("all") || selected.isEmpty(), "全部部门模式不需要指定部门");
    var version = body.path("revision");
    require(version.isIntegralNumber() && version.canConvertToLong() && version.asLong() >= 0 && version.asLong() < Long.MAX_VALUE, "请提供有效配置版本，刷新后重试");
    var departments = departments();
    var existing = new HashSet<String>(); departments.forEach(d -> existing.add(d.get("id")));
    require(existing.containsAll(selected), "所选部门不存在，请刷新后重试");
    db.jdbc().update(ENSURE);
    var before = configuration(" FOR UPDATE");
    if (before.revision() != version.asLong()) throw new ApiException(409, "部门统计范围已被其他人更新，请刷新后重试");
    int changed = db.jdbc().update("UPDATE sales_target_scope SET mode=?,department_ids=?,revision=revision+1,updated_at=?,updated_by=? WHERE id=1 AND revision=?",
        mode, db.encode(selected), CrmRepository.now(), actor.name(), version.asLong());
    if (changed != 1) throw new ApiException(409, "部门统计范围已被其他人更新，请刷新后重试");
    var after = configuration("");
    var detail = db.object().put("type", "SALES_TARGET_SCOPE_CHANGED");
    detail.set("before", db.tree(before.metadata())); detail.set("after", db.tree(after.metadata()));
    db.audit(actor.name(), "配置销售目标部门统计范围", detail);
    return adminView(after, departments);
  }
}
