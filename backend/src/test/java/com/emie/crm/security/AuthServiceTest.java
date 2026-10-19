package com.emie.crm.security;
import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AuthServiceTest {
 @Test void feishuEnablingAlsoRequiresAuthentication(){
  var auth=new AuthService(mock(CrmRepository.class),mock(PasswordCodec.class),"0","1");
  assertTrue(auth.loginRequired());assertThrows(ApiException.class,()->auth.actor(new MockHttpServletRequest()));
 }
 @Test void disabledAccountInvalidatesPreviouslyIssuedSession(){
  var db=mock(CrmRepository.class);
  when(db.rows("SELECT role FROM users WHERE name=?","member")).thenReturn(List.of(Map.of("role","销售")));
  var auth=new AuthService(db,mock(PasswordCodec.class),"1","0");
  var response=auth.createSession("member");var req=new MockHttpServletRequest();req.addHeader("Authorization","Bearer "+response.get("token"));
  assertThrows(ApiException.class,()->auth.actor(req));
  auth.logout(req);assertThrows(ApiException.class,()->auth.actor(req));
 }
}
