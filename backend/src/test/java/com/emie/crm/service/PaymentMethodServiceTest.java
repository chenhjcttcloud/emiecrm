package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentMethodServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CrmRepository db = mock(CrmRepository.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final PaymentMethodService service = new PaymentMethodService(db);
  private final Actor admin = new Actor("admin", "管理员", Set.of("system.manage"));

  @BeforeEach void prepare() { when(db.jdbc()).thenReturn(jdbc); }

  private ObjectNode input() {
    return mapper.createObjectNode().put("name", "自定义POS").put("status", "启用").put("sortOrder", 2);
  }

  @Test void ordinaryBusinessReadersCannotChangeConfiguredMethods() {
    assertThrows(ApiException.class, () -> service.save(input(), new Actor("sales", "销售", Set.of("business.read", "business.write"))));
    verifyNoInteractions(jdbc); verify(db, never()).state(true);
  }

  @Test void configuredOrderAndDisabledRowsAreReturnedForAdministration() {
    var expected = List.<Map<String,Object>>of(Map.of("id", "old", "name", "历史方式", "status", "停用", "sortOrder", 1),
        Map.of("id", "new", "name", "自定义POS", "status", "启用", "sortOrder", 2));
    when(db.rows("SELECT id,name,status,sort_order AS sortOrder FROM payment_methods ORDER BY sort_order,id")).thenReturn(expected);
    assertEquals(expected, service.list());
  }

  @Test void renameAndDisableOnlyUpdateConfigurationAndNeverRewriteFinancialHistory() {
    when(db.rows("SELECT id FROM payment_methods WHERE id=?", "bank")).thenReturn(List.of(Map.of("id", "bank")));
    var input = input().put("id", "bank").put("status", "停用").put("name", "  新账户名称  ");
    service.save(input, admin);
    verify(db).state(true);
    verify(jdbc).update(startsWith("INSERT INTO payment_methods"), eq("bank"), eq("新账户名称"), eq("停用"), eq(2));
    verifyNoMoreInteractions(jdbc); verify(db, never()).receipt(any()); verify(db, never()).state(any(com.fasterxml.jackson.databind.JsonNode.class));
  }

  @Test void namesAndIdentifiersAreTextualAndCannotImpersonateTheWriteOffOperation() {
    var invalid = input().put("name", 123);
    assertThrows(ApiException.class, () -> service.save(invalid, admin));
    invalid.put("name", "预收款核销\u3000");
    assertThrows(ApiException.class, () -> service.save(invalid, admin));
    invalid.put("name", "现金\n核销");
    assertThrows(ApiException.class, () -> service.save(invalid, admin));
    invalid.put("name", "\n现金");
    assertThrows(ApiException.class, () -> service.save(invalid, admin));
    invalid.put("name", "正常方式").put("id", 123);
    assertThrows(ApiException.class, () -> service.save(invalid, admin));
    invalid.remove("id"); invalid.putNull("status");
    assertThrows(ApiException.class, () -> service.save(invalid, admin));
    verifyNoInteractions(jdbc);
  }

  @Test void duplicateNamesInvalidSortingAndUnknownIdsCannotMutateConfiguration() {
    var duplicate = input();
    when(db.rows("SELECT id FROM payment_methods WHERE name=? AND id<>?", "自定义POS", "")).thenReturn(List.of(Map.of("id", "existing")));
    assertThrows(ApiException.class, () -> service.save(duplicate, admin));
    var invalidSort = input().put("sortOrder", -1);
    assertThrows(ApiException.class, () -> service.save(invalidSort, admin));
    invalidSort.put("sortOrder", 1.5);
    assertThrows(ApiException.class, () -> service.save(invalidSort, admin));
    assertThrows(ApiException.class, () -> service.save(input().put("id", "unknown"), admin));
    verifyNoInteractions(jdbc);
  }
}
