package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JushuitanCatalogJobTest {
  final ObjectMapper m=new ObjectMapper();
  final Actor actor=new Actor("admin","管理员",Set.of("business.write"));
  CrmRepository db;JushuitanClient client;PlatformTransactionManager manager;JushuitanCatalogJob service;
  ObjectNode state;String checkpoint;Clock clock=Clock.fixed(Instant.parse("2026-10-22T04:00:00Z"),ZoneOffset.UTC);
  JsonNode json(String text)throws Exception{return m.readTree(text);}
  JsonNode response(String rows,boolean more)throws Exception{return json("{\"code\":0,\"data\":{\"datas\":"+rows+",\"has_next\":"+more+"}}");}
  JushuitanCatalogJob fresh(){var s=new JushuitanCatalogJob(db,client,m,manager,"production");ReflectionTestUtils.setField(s,"clock",clock);return s;}
  void begin(String date)throws Exception{service.start(json("{\"startDate\":\""+date+"\"}"),actor);}
  @BeforeEach void setup()throws Exception{
    state=(ObjectNode)json("{\"revision\":1,\"customers\":[],\"orders\":[{\"id\":\"unchanged\"}],\"products\":[{\"id\":\"legacy\",\"name\":\"旧商品\"}]}");
    db=mock(CrmRepository.class);client=mock(JushuitanClient.class);manager=mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(client.configured()).thenReturn(true);
    when(client.query(eq("/open/sku/query"),any())).thenReturn(response("[]",false));
    when(db.state(anyBoolean())).thenAnswer(i->state.deepCopy());
    doAnswer(i->{state=((JsonNode)i.getArgument(0)).deepCopy();return null;}).when(db).state(any(JsonNode.class));
    when(db.parse(anyString())).thenAnswer(i->m.readTree((String)i.getArgument(0)));
    when(db.encode(any())).thenAnswer(i->m.writeValueAsString(i.getArgument(0)));
    when(db.rows(anyString(),eq("production"))).thenAnswer(i->checkpoint==null?List.of():List.of(Map.of("payload",checkpoint)));
    var jdbc=mock(JdbcTemplate.class);when(db.jdbc()).thenReturn(jdbc);
    when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(i->{
      Object[] args=(Object[])i.getRawArguments()[1];String sql=i.getArgument(0);
      if(sql.startsWith("INSERT IGNORE")&&checkpoint==null)checkpoint=(String)args[1];
      if(sql.startsWith("INSERT INTO"))checkpoint=(String)args[1];return 1;
    });service=fresh();
  }
  @Test void fullUsesSevenDayCreatedWindowsAndKeepsPagesInSameWindow()throws Exception{
    begin("2026-10-01");var job=(ObjectNode)m.readTree(checkpoint);
    var first=JushuitanCatalogJob.parameters(job);
    assertEquals("created",first.get("date_field"));assertEquals("2026-10-15 12:00:00",first.get("modified_begin"));
    JushuitanCatalogJob.advance(job,true,100,clock);
    assertEquals(2,job.path("page").asInt());assertEquals(first.get("modified_begin"),JushuitanCatalogJob.parameters(job).get("modified_begin"));
    JushuitanCatalogJob.advance(job,false,3,clock);
    assertEquals("2026-10-15 12:00:00",job.path("cursor").asText());assertEquals(1,job.path("page").asInt());
    assertEquals("2026-10-08 12:00:00",JushuitanCatalogJob.parameters(job).get("modified_begin"));
    assertTrue(JushuitanCatalogJob.progress(job)>0);
  }
  @Test void importsImageAndCompletesWithoutChangingOrders()throws Exception{
    when(client.query(anyString(),any())).thenReturn(response("[{\"sku_id\":\"A\",\"name\":\"商品\",\"enabled\":1,\"pic\":\"https://cdn.example.com/a.png\"}]",false));
    begin("2026-10-21");service.tick();
    assertEquals("COMPLETE",service.status().path("status").asText());assertEquals(100,service.status().path("progress").asInt());
    assertEquals("https://cdn.example.com/a.png",state.path("products").get(0).path("jstImage").asText());
    assertEquals("unchanged",state.path("orders").get(0).path("id").asText());assertEquals(2,state.path("revision").asInt());
    service.tick();verify(client,times(1)).query(anyString(),any());
    assertFalse(service.status().has("verifySkus"));assertFalse(service.status().has("claim"));
  }
  @Test void failuresPreserveCursorAndResumeAfterNewServiceInstance()throws Exception{
    when(client.query(anyString(),any())).thenThrow(new ApiException("暂时不可用")).thenReturn(response("[]",false));
    begin("2026-10-21");String cursor=service.status().path("cursor").asText();service.tick();
    assertEquals("FAILED",service.status().path("status").asText());assertEquals(cursor,service.status().path("cursor").asText());
    assertEquals(1,state.path("revision").asInt());
    service=fresh();service.control("resume",actor);service.tick();assertEquals("COMPLETE",service.status().path("status").asText());
  }
  @Test void restartResumesExactPageAndRejectsDuplicateStart()throws Exception{
    when(client.query(anyString(),any())).thenReturn(response("[{\"sku_id\":\"A\",\"enabled\":1}]",true)).thenReturn(response("[]",false));
    begin("2026-10-21");service.tick();assertEquals(2,service.status().path("page").asInt());
    assertThrows(ApiException.class,()->begin("2026-10-21"));
    service.control("pause",actor);service=fresh();service.control("resume",actor);service.tick();
    verify(client).query(eq("/open/sku/query"),argThat(x->x instanceof Map<?,?> p&&Integer.valueOf(2).equals(p.get("page_index"))));
    assertEquals("COMPLETE",service.status().path("status").asText());
  }
  @Test void pausedInFlightResponseCannotCommitAfterResumeAndReclaim()throws Exception{
    begin("2026-10-21");
    when(client.query(anyString(),any())).thenAnswer(i->{service.control("pause",actor);service.control("resume",actor);ReflectionTestUtils.invokeMethod(service,"claim");return response("[{\"sku_id\":\"STALE\",\"enabled\":1}]",false);});
    service.tick();assertEquals(1,state.path("revision").asInt());assertEquals(0,service.status().path("processedRows").asInt());
  }
  @Test void overlapAcrossTimeWindowsKeepsStableIdentity()throws Exception{
    when(client.query(anyString(),any())).thenReturn(response("[{\"sku_id\":\"A\",\"enabled\":1}]",false));
    begin("2026-10-08");service.tick();String id=state.path("products").get(0).path("id").asText();service.tick();service.tick();
    assertEquals("COMPLETE",service.status().path("status").asText());
    assertEquals(2,state.path("products").size());assertEquals(id,state.path("products").get(0).path("id").asText());
  }
  @Test void missingProductIsOnlyDisabledAfterSuccessfulExplicitVerification()throws Exception{
    state.putArray("products").addObject().put("id","known").put("source","jushuitan").put("jstEnvironment","production").put("jstSku","A").put("enabled",true);
    begin("2026-10-21");service.tick();
    assertEquals("VERIFY",service.status().path("phase").asText());assertTrue(state.path("products").get(0).path("enabled").asBoolean());
    when(client.query(anyString(),any())).thenThrow(new ApiException("核对失败"));service.tick();assertTrue(state.path("products").get(0).path("enabled").asBoolean());
    doReturn(response("[]",false)).when(client).query(anyString(),any());service.control("resume",actor);service.tick();
    assertFalse(state.path("products").get(0).path("enabled").asBoolean());assertEquals("COMPLETE",service.status().path("status").asText());
  }
  @Test void automaticIncrementalUsesCompletedWatermarkWithOverlap()throws Exception{
    begin("2026-10-21");service.tick();
    clock=Clock.offset(clock,Duration.ofMinutes(11));ReflectionTestUtils.setField(service,"clock",clock);service.tick();
    assertEquals("INCREMENTAL",service.status().path("mode").asText());
    verify(client).query(eq("/open/sku/query"),argThat(x->x instanceof Map<?,?> p&&"modified".equals(p.get("date_field"))&&"2026-10-22 11:55:00".equals(p.get("modified_begin"))));
  }
  @Test void shutdownInterruptionLeavesRunningCheckpointForAutomaticResume() throws Exception {
    begin("2026-10-21");
    when(client.query(anyString(),any())).thenAnswer(i->{Thread.currentThread().interrupt();throw new ApiException("调用中断");});
    try {
      service.tick();assertEquals("RUNNING",service.status().path("status").asText());
      assertEquals(0,m.readTree(checkpoint).path("leaseUntil").asLong());assertEquals(1,state.path("revision").asInt());
    } finally {Thread.interrupted();}
    doReturn(response("[]",false)).when(client).query(anyString(),any());service=fresh();service.tick();
    assertEquals("COMPLETE",service.status().path("status").asText());
  }
  @Test void malformedPageDoesNotOverwriteCacheOrAdvance()throws Exception{
    when(client.query(anyString(),any())).thenReturn(json("{\"code\":0,\"data\":{\"datas\":[]}}"));
    begin("2026-10-21");service.tick();assertEquals("FAILED",service.status().path("status").asText());assertEquals(1,state.path("revision").asInt());
  }
  @Test void fullOnlyCountsCompleteDateRangeAndRequiresWritePermission()throws Exception{
    assertThrows(ApiException.class,()->service.start(m.createObjectNode(),new Actor("reader","只读",Set.of("business.read"))));
    begin("2026-10-20");var job=(ObjectNode)m.readTree(checkpoint);
    assertEquals("2026-10-20 00:00:00",JushuitanCatalogJob.parameters(job).get("modified_begin"));
    JushuitanCatalogJob.advance(job,false,0,clock);assertEquals("COMPLETE",job.path("status").asText());
  }
}
