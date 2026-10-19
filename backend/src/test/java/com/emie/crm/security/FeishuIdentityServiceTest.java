package com.emie.crm.security;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.common.exception.ApiException;
import com.lark.oapi.service.authen.v1.model.GetUserInfoRespBody;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FeishuIdentityServiceTest {
 @Test void newIdentityGetsPendingRoleAndNeverBindsByDisplayNameOrEmail(){
  var db=mock(CrmRepository.class);var jdbc=mock(JdbcTemplate.class);when(db.jdbc()).thenReturn(jdbc);
  when(db.rows(anyString(),any(Object[].class))).thenAnswer(i->{String sql=i.getArgument(0);if(sql.contains("u.role='管理员'"))return List.of(Map.of("name","admin"));if(sql.contains("WHERE u.name=?"))return List.of(Map.of("name","member","status","启用"));return List.of();});
  var passwords=mock(PasswordCodec.class);when(passwords.hash(anyString(),anyString())).thenReturn("hash");
  var info=mock(GetUserInfoRespBody.class);when(info.getOpenId()).thenReturn("ou_new");when(info.getName()).thenReturn("管理员");when(info.getEmail()).thenReturn("admin@example.test");
  String name=new FeishuIdentityService(db,passwords).resolve("app1",info);
  assertTrue(name.startsWith("fs_"));
  verify(jdbc).update(eq("INSERT INTO users(name,salt,password,role) VALUES(?,?,?,?)"),eq(name),anyString(),eq("hash"),eq("待授权"));
  verify(info,never()).getEmail();
 }
 @Test void missingAdministratorPreventsSsoFromLockingOutInitialSetup(){
  var db=mock(CrmRepository.class);var info=mock(GetUserInfoRespBody.class);when(info.getOpenId()).thenReturn("ou_first");
  assertThrows(ApiException.class,()->new FeishuIdentityService(db,mock(PasswordCodec.class)).resolve("app",info));
  verify(db,never()).jdbc();
 }
}
