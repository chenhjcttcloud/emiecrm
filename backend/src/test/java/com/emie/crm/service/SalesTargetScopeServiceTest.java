package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesTargetScopeServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Actor admin = new Actor("admin", "配置管理员", Set.of("system.manage"));
  private CrmRepository db;
  private JdbcTemplate jdbc;
  private SalesTargetScopeService service;
  private List<Map<String, Object>> configurations;
  private List<Map<String, Object>> departments;
  private List<Map<String, Object>> accounts;

  @BeforeEach void setup() {
    configurations = new ArrayList<>(); departments = new ArrayList<>(); accounts = new ArrayList<>();
    departments.add(Map.of("id", "d1", "name", "一部")); departments.add(Map.of("id", "d2", "name", "二部"));
    db = mock(CrmRepository.class); jdbc = mock(JdbcTemplate.class);
    when(db.jdbc()).thenReturn(jdbc);
    when(db.object()).thenAnswer(call -> mapper.createObjectNode());
    when(db.parse(anyString())).thenAnswer(call -> mapper.readTree((String)call.getArgument(0)));
    when(db.encode(any())).thenAnswer(call -> mapper.writeValueAsString(call.getArgument(0)));
    when(db.tree(any())).thenAnswer(call -> mapper.valueToTree(call.getArgument(0)));
    when(db.rows(anyString(), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.contains("FROM sales_target_scope")) return List.copyOf(configurations);
      if (sql.contains("LEFT JOIN departments")) return List.copyOf(accounts);
      if (sql.contains("FROM departments")) return List.copyOf(departments);
      return List.of();
    });
    when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.startsWith("INSERT INTO sales_target_scope") && configurations.isEmpty()) set("all", List.of(), 0);
      if (sql.startsWith("UPDATE sales_target_scope")) {
        var updated = new LinkedHashMap<String, Object>();
        updated.put("mode", call.getArgument(1)); updated.put("department_ids", call.getArgument(2));
        updated.put("revision", ((Number) call.getArgument(5)).longValue() + 1);
        updated.put("updated_at", call.getArgument(3)); updated.put("updated_by", call.getArgument(4));
        configurations.clear(); configurations.add(updated);
      }
      return 1;
    });
    service = new SalesTargetScopeService(db);
  }

  private void set(String mode, List<String> ids, long revision) {
    var row = new LinkedHashMap<String, Object>(); row.put("mode", mode);
    row.put("department_ids", mapper.valueToTree(ids).toString()); row.put("revision", revision);
    row.put("updated_at", null); row.put("updated_by", null);
    configurations.clear(); configurations.add(row);
  }

  private Map<String, Object> account(String owner, String id, String name) {
    var row = new LinkedHashMap<String, Object>(); row.put("owner", owner); row.put("departmentId", id); row.put("department", name);
    return row;
  }

  private ObjectNode body(String mode, List<String> ids, long revision) {
    var result = mapper.createObjectNode().put("mode", mode).put("revision", revision);
    result.set("departmentIds", mapper.valueToTree(ids)); return result;
  }

  @Test void defaultAllIsReadOnlyAndIncludesFutureDepartmentsAndLegacyOwners() {
    var view = mapper.valueToTree(service.view(admin));
    assertEquals("all", view.path("mode").asText()); assertEquals(0, view.path("revision").asLong());
    assertTrue(view.path("departmentIds").isEmpty()); assertEquals(2, view.path("departments").size());
    assertTrue(service.snapshot().includes("历史未匹配人员"));
    departments.add(Map.of("id", "new", "name", "以后新增"));
    assertEquals(3, service.snapshot().selectableDepartments().size());
    assertTrue(configurations.isEmpty()); verifyNoInteractions(jdbc);
  }

  @Test void selectedScopeRequiresUniqueAssignedCurrentDepartmentAndKeepsDisabledDepartments() {
    set("selected", List.of("d1"), 1);
    accounts.add(account("唯一", "d1", "一部")); accounts.add(account("外部", "d2", "二部"));
    accounts.add(account("未分配", null, null));
    accounts.add(account("同名", "d1", "一部")); accounts.add(account("同名", "d1", "一部"));
    var snapshot = service.snapshot();
    assertTrue(snapshot.includes("唯一"));
    for (String owner : List.of("外部", "未分配", "历史无账户", "同名")) assertFalse(snapshot.includes(owner), owner);
    assertEquals("ambiguous", snapshot.department("同名").get("departmentStatus"));
    assertEquals(1, snapshot.selectableDepartments().size());
    assertEquals("d1", snapshot.selectableDepartments().getFirst().get("id"));
  }

  @Test void emptySelectionAndDeletedDepartmentsAreIgnoredWithoutFallingBackToAll() {
    set("selected", List.of(), 1); accounts.add(account("甲", "d1", "一部"));
    assertFalse(service.snapshot().includes("甲")); assertTrue(service.snapshot().selectableDepartments().isEmpty());
    set("selected", List.of("deleted", "d1"), 2);
    var view = mapper.valueToTree(service.view(admin));
    assertEquals("deleted", view.path("missingDepartmentIds").get(0).asText());
    assertTrue(service.snapshot().includes("甲"));
    departments.removeIf(d -> d.get("id").equals("d1"));
    assertFalse(service.snapshot().includes("甲"));
    assertTrue(service.snapshot().selected());
  }

  @Test void snapshotsStayImmutableAndTheNextReadPicksUpScopeAndDepartmentMoves() {
    accounts.add(account("甲", "d1", "一部"));
    var all = service.snapshot(); set("selected", List.of("d1"), 1);
    assertTrue(all.includes("外部历史")); assertFalse(service.snapshot().includes("外部历史"));
    assertTrue(service.snapshot().includes("甲"));
    accounts.set(0, account("甲", "d2", "二部")); assertFalse(service.snapshot().includes("甲"));
  }

  @Test void onlySystemManagersCanReadOrChangeSettings() {
    clearInvocations(db, jdbc);
    for (var actor : List.of(new Actor("reader", "销售", Set.of("business.read")),
        new Actor("target", "目标管理", Set.of("dashboard.targets.manage")))) {
      assertEquals(403, assertThrows(ApiException.class, () -> service.view(actor)).status());
      assertEquals(403, assertThrows(ApiException.class, () -> service.save(body("all", List.of(), 0), actor)).status());
    }
    verifyNoInteractions(db, jdbc);
  }

  @Test void saveAuditsExactConfigurationAndUsesCasWithoutChangingBusinessTables() {
    var saved = mapper.valueToTree(service.save(body("selected", List.of("d1"), 0), admin));
    assertEquals("selected", saved.path("mode").asText()); assertEquals(1, saved.path("revision").asInt());
    assertEquals("d1", saved.path("departmentIds").get(0).asText()); assertEquals(2, saved.path("departments").size());
    var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).audit(eq("admin"), anyString(), capture.capture());
    assertEquals("SALES_TARGET_SCOPE_CHANGED", capture.getValue().path("type").asText());
    assertEquals("all", capture.getValue().path("before").path("mode").asText());
    assertEquals(0, capture.getValue().path("before").path("revision").asInt());
    assertEquals(1, capture.getValue().path("after").path("revision").asInt());
    verify(db, never()).state(any(JsonNode.class)); verify(db, never()).receipt(any());
  }

  @Test void staleConfigurationVersionAndFailedCasCannotAuditSuccess() {
    set("selected", List.of("d1"), 3);
    assertEquals(409, assertThrows(ApiException.class, () -> service.save(body("selected", List.of("d2"), 2), admin)).status());
    verify(jdbc, never()).update(startsWith("UPDATE sales_target_scope"), any(Object[].class));
    when(jdbc.update(startsWith("UPDATE sales_target_scope"), any(Object[].class))).thenReturn(0);
    assertEquals(409, assertThrows(ApiException.class, () -> service.save(body("all", List.of(), 3), admin)).status());
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
  }

  @Test void invalidModesIdsDuplicatesLengthsAndRevisionFailBeforeAnyWrite() {
    for (var body : List.of(body("other", List.of(), 0), body("all", List.of("d1"), 0),
        body("selected", List.of("d1", "d1"), 0), body("selected", List.of("missing"), 0),
        body("selected", List.of("x".repeat(65)), 0), body("selected", List.of(" "), 0),
        body("selected", List.of(), -1), body("selected", Collections.nCopies(1001, "d1"), 0)))
      assertEquals(400, assertThrows(ApiException.class, () -> service.save(body, admin)).status());
    var revisionMalformed = body("selected", List.of(), 0); revisionMalformed.put("revision", 0.5);
    assertThrows(ApiException.class, () -> service.save(revisionMalformed, admin));
    var idsMalformed = body("selected", List.of(), 0); idsMalformed.putArray("departmentIds").add(123);
    assertThrows(ApiException.class, () -> service.save(idsMalformed, admin));
    verifyNoInteractions(jdbc);
  }

  @Test void firstWriteInitializationLocksWithoutReplacingExistingConfiguration() throws Exception {
    set("selected", List.of("d1"), 5);
    var snapshot = service.snapshotForWrite();
    assertEquals("selected", snapshot.metadata().get("mode")); assertEquals(5L, snapshot.metadata().get("revision"));
    var sequence = inOrder(jdbc, db);
    sequence.verify(jdbc).update(contains("ON DUPLICATE KEY UPDATE id=id"));
    sequence.verify(db).rows(contains("WHERE id=1 FOR SHARE"));
    assertEquals(Isolation.REPEATABLE_READ, SalesTargetScopeService.class.getMethod("save", JsonNode.class, Actor.class)
        .getAnnotation(Transactional.class).isolation());
  }
}
