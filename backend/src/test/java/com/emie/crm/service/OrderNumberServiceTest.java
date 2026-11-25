package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderNumberServiceTest {
  @Test void shortNumbersRequireARealDateAndAPositiveMinimumFourDigitSequence() {
    for(String value:new String[]{"N2611250001","N2611259999","N26112510000","N2402290001","N0012310001"})
      assertTrue(OrderNumberService.isShortNumber(value),value);
    for(String value:new String[]{"N2611250000","N261125001","N2602290001","N2613320001","N2611310001",
        "N261125-001","n2611250001","N2611250001 ","S261125151751967","2026-11-00014583"})
      assertFalse(OrderNumberService.isShortNumber(value),value);
    assertFalse(OrderNumberService.isShortNumber("N2611259223372036854775808"));
    assertFalse(OrderNumberService.isShortNumber("N261125"+"0".repeat(26)+"1"));
    assertFalse(OrderNumberService.isShortNumber(null));
  }

  @Test void unauthorizedOrMalformedRequestsNeverReachTheDatabase() {
    var db=mock(CrmRepository.class);var manager=mock(PlatformTransactionManager.class);var service=new OrderNumberService(db,manager);
    var writer=new Actor("sales","销售",Set.of("business.read","business.write"));
    for(String id:new String[]{"short"," "+"a".repeat(16),"a".repeat(129),"a".repeat(16)+"\n"})
      assertThrows(ApiException.class,()->service.reserve(id,writer));
    assertThrows(ApiException.class,()->service.reserve("number-request-000001",new Actor("reader","只读",Set.of("business.read"))));
    verifyNoInteractions(db,manager);
  }
}
