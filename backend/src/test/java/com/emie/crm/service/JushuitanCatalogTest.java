package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JushuitanCatalogTest {
  final ObjectMapper m=new ObjectMapper();
  @Test void keepsHistoryAndStableIdsAndDisablesMissingProducts() throws Exception {
    var old=m.readTree("""
      [{"id":"legacy","name":"旧商品"},{"id":"known","source":"jushuitan","jstEnvironment":"production","jstSku":"A","enabled":true},
       {"id":"gone","source":"jushuitan","jstEnvironment":"production","jstSku":"B","enabled":true}]
      """);
    var remote=m.readTree("[{\"sku_id\":\"A\",\"name\":\"最新名称\",\"enabled\":1,\"sale_price\":12.50}]");
    var rows=JushuitanCatalog.merge(m,old,remote,"production");
    assertEquals("known",rows.get(0).path("id").asText());
    assertEquals("最新名称",rows.get(0).path("name").asText());
    assertEquals(3,rows.size());
    assertFalse(rows.get(1).path("enabled").asBoolean());
    assertFalse(rows.get(2).path("enabled").asBoolean());
    var again=JushuitanCatalog.merge(m,rows,remote,"production");
    assertEquals(rows.size(),again.size());
    assertEquals(rows.get(0).path("id"),again.get(0).path("id"));
  }
  @Test void synchronizesVendorPictureAndClearsRemovedPicture() throws Exception {
    var row=m.readTree("{\"sku_id\":\"A\",\"enabled\":1,\"pic\":\"https://cdn.example.com/thumb.jpg\",\"pic_big\":\"https://cdn.example.com/large.jpg\"}");
    var rows=JushuitanCatalog.merge(m,m.createArrayNode(),m.createArrayNode().add(row),"production");
    assertEquals("https://cdn.example.com/large.jpg",rows.get(0).path("jstImage").asText());
    assertFalse(rows.get(0).has("image")); // Remote link never becomes a CRM asset attachment.
    rows=JushuitanCatalog.merge(m,rows,m.readTree("[{\"sku_id\":\"A\",\"enabled\":1,\"pic\":null,\"pic_big\":null}]"),"production");
    assertEquals("",rows.get(0).path("jstImage").asText());
  }
  @Test void rejectsUnsafePictureUrlsAndFallsBackToThumbnail() throws Exception {
    assertEquals("https://cdn.example.com/a.jpg",JushuitanCatalog.image(m.readTree("{\"pic_big\":\"javascript:alert(1)\",\"pic\":\"//cdn.example.com/a.jpg\"}")));
    assertEquals("",JushuitanCatalog.image(m.readTree("{\"pic\":\"https://user:secret@example.com/a.jpg\"}")));
  }
  @Test void targetedRefreshDoesNotDisableUnrelatedProducts() throws Exception {
    var old=m.readTree("[{\"id\":\"keep\",\"source\":\"jushuitan\",\"jstEnvironment\":\"production\",\"jstSku\":\"B\",\"enabled\":true}]");
    var rows=JushuitanCatalog.merge(m,old,m.createArrayNode(),"production",java.util.Set.of("A"));
    assertTrue(rows.get(0).path("enabled").asBoolean());
    rows=JushuitanCatalog.merge(m,old,m.createArrayNode(),"production",java.util.Set.of("B"));
    assertFalse(rows.get(0).path("enabled").asBoolean());
  }
  @Test void rejectsDuplicateSkuInsteadOfSilentlyOverwriting() throws Exception {
    var rows=m.readTree("[{\"sku_id\":\"A\"},{\"sku_id\":\"A\"}]");
    assertThrows(ApiException.class,()->JushuitanCatalog.merge(m,m.createArrayNode(),rows,"production"));
  }
  @Test void environmentSwitchDoesNotReuseSandboxProduct() throws Exception {
    var old=m.readTree("[{\"id\":\"sandbox-id\",\"source\":\"jushuitan\",\"jstEnvironment\":\"sandbox\",\"jstSku\":\"A\"}]");
    var rows=JushuitanCatalog.merge(m,old,m.readTree("[{\"sku_id\":\"A\",\"enabled\":1}]"),"production");
    assertNotEquals("sandbox-id",rows.get(0).path("id").asText());
    assertTrue(rows.get(0).path("enabled").asBoolean());
    assertFalse(rows.get(1).path("enabled").asBoolean());
  }
}
