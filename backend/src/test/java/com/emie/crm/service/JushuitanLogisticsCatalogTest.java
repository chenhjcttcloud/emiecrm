package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JushuitanLogisticsCatalogTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private JushuitanClient client;
  private JushuitanService service;
  private JsonNode json(String text) throws Exception { return mapper.readTree(text); }
  private ObjectNode request(String code,String name) {
    return mapper.createObjectNode().put("wms_co_id",15136229).put("lc_id",code).put("logistics_company",name);
  }
  @BeforeEach void setup() throws Exception {
    client=mock(JushuitanClient.class);
    service=new JushuitanService(mock(CrmRepository.class),client,mapper,mock(PlatformTransactionManager.class),"sandbox");
    when(client.query(eq("/open/wms/partner/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"wms_co_id\":15136229,\"name\":\"仓库甲\",\"status\":\"生效\"}]}}"));
    when(client.query(eq("/open/logisticscompany/query"),any())).thenReturn(json("""
        {"code":0,"data":{"datas":[{"lc_id":"SF.5","lc_name":"顺丰到付"},{"lc_id":"00012","lc_name":"特殊编码物流"},
        {"lc_id":"ZTZS","lc_name":"自提自送"}],"has_next":false}}
        """));
  }

  @Test void returnsOnlyRealWarehouseConfiguredCompaniesAndPreservesOpaqueCodes() {
    var companies=service.logisticsCatalog(15136229);
    assertEquals(3,companies.size());assertEquals("SF.5",companies.get(0).path("code").asText());
    assertEquals("00012",companies.get(1).path("code").asText());assertEquals("自提自送",companies.get(2).path("name").asText());
    verify(client).query(eq("/open/logisticscompany/query"),eq(Map.of("wms_co_id",15136229L,"page_index",1,"page_size",50)));
    verify(client,never()).query(eq("/open/logistic/query"),any());
  }

  @Test void validatesTheWarehouseBeforeReadingTheCatalog() {
    assertThrows(ApiException.class,()->service.logisticsCatalog(0));
    assertThrows(ApiException.class,()->service.logisticsCatalog(-1));
    assertThrows(ApiException.class,()->service.logisticsCatalog(999));
    verify(client,never()).query(eq("/open/logisticscompany/query"),any());
  }

  @Test void defaultWarehouseArrangementNeverDependsOnTheLogisticsCatalog() {
    when(client.query(eq("/open/logisticscompany/query"),any())).thenThrow(new ApiException(503,"目录不可用"));
    assertDoesNotThrow(()->service.requireLogistics(mapper.createObjectNode().put("wms_co_id",15136229)));
    verifyNoInteractions(client);
    assertThrows(ApiException.class,()->service.requireLogistics(request("SF.5","顺丰到付")));
  }

  @Test void selectedCodeAndNameMustMatchTheSameWarehouseCatalog() {
    assertDoesNotThrow(()->service.requireLogistics(request("SF.5","顺丰到付")));
    assertDoesNotThrow(()->service.requireLogistics(request("ZTZS","自提自送")));
    assertThrows(ApiException.class,()->service.requireLogistics(request("SF.5","顺丰速运")));
    assertThrows(ApiException.class,()->service.requireLogistics(request("OTHER","自造物流")));
    var partial=request("SF.5","顺丰到付");partial.remove("logistics_company");
    assertThrows(ApiException.class,()->service.requireLogistics(partial));
    assertThrows(ApiException.class,()->service.requireLogistics(request("SF.5","顺丰到付").put("wms_co_id",999)));
  }

  @Test void completesPaginationBeforeReturningAndRejectsAmbiguousCodes() throws Exception {
    when(client.query(eq("/open/logisticscompany/query"),any())).thenReturn(
        json("{\"code\":0,\"data\":{\"datas\":[{\"lc_id\":\"SF\",\"lc_name\":\"顺丰速运\"}],\"has_next\":true}}"),
        json("{\"code\":0,\"data\":{\"datas\":[{\"lc_id\":\"SF.5\",\"lc_name\":\"顺丰到付\"}],\"has_next\":false}}"));
    assertEquals(2,service.logisticsCatalog(15136229).size());
    verify(client).query(eq("/open/logisticscompany/query"),eq(Map.of("wms_co_id",15136229L,"page_index",2,"page_size",50)));
    when(client.query(eq("/open/logisticscompany/query"),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[{\"lc_id\":\"SF\",\"lc_name\":\"顺丰\"},{\"lc_id\":\"SF\",\"lc_name\":\"另一个名称\"}],\"has_next\":false}}"));
    assertThrows(ApiException.class,()->service.logisticsCatalog(15136229));
  }

  @Test void rejectsFailedPartialAndMalformedCatalogResponsesInsteadOfClaimingAvailability() throws Exception {
    for(String body:new String[]{"{\"code\":500}","{\"code\":0,\"data\":{\"datas\":[],\"has_next\":\"false\"}}",
        "{\"code\":0,\"data\":{\"datas\":{},\"has_next\":false}}",
        "{\"code\":0,\"data\":{\"datas\":[{\"lc_id\":123,\"lc_name\":\"物流\"}],\"has_next\":false}}"}) {
      when(client.query(eq("/open/logisticscompany/query"),any())).thenReturn(json(body));
      assertThrows(ApiException.class,()->service.logisticsCatalog(15136229),body);
    }
    when(client.query(eq("/open/logisticscompany/query"),any())).thenReturn(
        json("{\"code\":0,\"data\":{\"datas\":[],\"has_next\":true}}"))
        .thenThrow(new ApiException("第二页查询失败"));
    assertThrows(ApiException.class,()->service.logisticsCatalog(15136229));
  }
}
