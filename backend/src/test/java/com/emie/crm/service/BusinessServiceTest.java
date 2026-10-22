package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private CrmRepository db;
  private BusinessService service;
  private JsonNode stored;
  private List<JsonNode> history;
  private final Actor admin = new Actor("admin", "管理员", Set.of("business.read", "business.write", "order.approve", "payment.write"));
  private final Actor sales = new Actor("sales", "销售", Set.of("business.read", "business.write"));

  private JsonNode json(String text) throws Exception { return mapper.readTree(text); }

  @BeforeEach void setup() throws Exception {
    stored = json("""
        {"revision":1,"customers":[{"id":"c1","name":"客户甲","balance":0}],
         "products":[{"id":"p1","name":"产品"}],
         "orders":[{"id":"o1","customerId":"c1","customer":"客户甲","type":"销售订单",
          "status":"已批准","total":100,"paid":0,"items":[{"product":"p1","qty":1,"price":100}]}]}
        """);
    history = new ArrayList<>();
    db = mock(CrmRepository.class);
    when(db.state(anyBoolean())).thenAnswer(call -> stored.deepCopy());
    when(db.documents(eq("receipts"), anyBoolean(), eq(0))).thenAnswer(call -> history.stream().map(JsonNode::deepCopy).toList());
    when(db.object()).thenAnswer(call -> mapper.createObjectNode());
    when(db.rows(anyString(), anyString())).thenReturn(List.of(Map.of("id", "bank")));
    doAnswer(call -> { stored = ((JsonNode) call.getArgument(0)).deepCopy(); return null; }).when(db).state(any(JsonNode.class));
    doAnswer(call -> { history.add(((JsonNode) call.getArgument(0)).deepCopy()); return null; }).when(db).receipt(any());
    service = new BusinessService(db, mock(AssetService.class));
  }

  private ObjectNode draft() { return stored.deepCopy(); }
  private ObjectNode order(JsonNode state) { return (ObjectNode) state.path("orders").get(0); }
  private JsonNode save(JsonNode state, Actor actor) {
    var body = mapper.createObjectNode(); body.set("data", state); body.put("revision", state.path("revision").asLong());
    return service.save(body, actor);
  }

  @Test void approvedAmountCannotBeChangedBySalesEvenWithMatchingItems() {
    var next = draft(); order(next).put("total", 200);
    ((ObjectNode) order(next).path("items").get(0)).put("price", 200);
    assertThrows(ApiException.class, () -> save(next, sales));
    verify(db, never()).state(any(JsonNode.class));
  }
  @Test void approvedStatusCannotBeDowngradedBySales() {
    var next = draft(); order(next).put("status", "草稿");
    assertThrows(ApiException.class, () -> save(next, sales));
  }
  @Test void salesCanEditApprovedNotesWithoutReapproval() {
    var next = draft(); order(next).put("note", "补充说明");
    assertEquals("补充说明", save(next, sales).path("orders").get(0).path("note").asText());
  }
  @Test void administratorCanEditUnpaidApprovedCoreFields() {
    var next = draft(); order(next).put("total", 200);
    ((ObjectNode) order(next).path("items").get(0)).put("price", 200);
    assertEquals(200, save(next, admin).path("orders").get(0).path("total").asInt());
  }
  @Test void subCentPaymentIsRejectedWithoutCreatingReceipt() {
    var next = draft(); order(next).put("paid", 0.004).put("method", "银行转账");
    assertThrows(ApiException.class, () -> save(next, admin));
    verify(db, never()).receipt(any());
  }
  @Test void clientCannotForgeOrderTotal() {
    var next = draft(); order(next).put("total", 200);
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void clientCannotUseMissingProductOrNegativePrice() {
    var next = draft(); ((ObjectNode) order(next).path("items").get(0)).put("product", "missing");
    assertThrows(ApiException.class, () -> save(next, admin));
    ((ObjectNode) order(next).path("items").get(0)).put("product", "p1").put("price", -100);
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void duplicateCustomerNamesAreRejected() {
    var next = draft(); ((ArrayNode) next.path("customers")).addObject().put("id", "c2").put("name", "客户甲");
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void renameKeepsCustomerIdAndDoesNotRewriteIdentifiedReceipts() throws Exception {
    history.add(json("""
      {"id":"r1","order":"o1","customerId":"c1","customer":"客户甲","amount":10,"type":"预收款订单","method":"银行转账"}
      """));
    var next = draft(); ((ObjectNode) next.path("customers").get(0)).put("name", "客户乙");
    var saved = save(next, sales);
    assertEquals("c1", order(saved).path("customerId").asText());
    assertEquals("客户乙", order(saved).path("customer").asText());
    assertEquals(10, saved.path("customers").get(0).path("balance").asInt());
    assertEquals("客户乙", service.receipts().getFirst().path("customer").asText());
    verify(db, never()).receipt(any());
  }
  @Test void legacyReferencesAreUpgradedOnce() throws Exception {
    order(stored).remove("customerId");
    history.add(json("""
      {"id":"r1","order":"o1","customer":"客户甲","amount":10,"type":"预收款订单","method":"银行转账"}
      """));
    var next = draft(); ((ObjectNode) next.path("customers").get(0)).put("name", "客户乙");
    save(next, sales);
    var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).receipt(capture.capture());
    assertEquals("c1", capture.getValue().path("customerId").asText());
  }
  @Test void existingPaidCoreFieldsRemainImmutableForAdministrator() {
    order(stored).put("paid", 10);
    var next = draft(); order(next).put("total", 200);
    ((ObjectNode) order(next).path("items").get(0)).put("price", 200);
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void onlyNewReceiptIsWrittenOnPayment() throws Exception {
    history.add(json("""
      {"id":"r1","order":"o1","customerId":"c1","customer":"客户甲","amount":10,"type":"销售订单","method":"银行转账"}
      """));
    order(stored).put("paid", 10);
    var next = draft(); order(next).put("paid", 20).put("method", "银行转账");
    save(next, admin);
    var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).receipt(capture.capture());
    assertEquals(10, capture.getValue().path("amount").asInt());
    assertNotEquals("r1", capture.getValue().path("id").asText());
  }
  @Test void ambiguousLegacyOrdersRemainReadableButCannotReceivePayments() {
    ((ArrayNode) stored.path("customers")).addObject().put("id", "c2").put("name", "客户甲").put("balance", 0);
    order(stored).remove("customerId");
    assertEquals("客户甲", order(service.state()).path("customer").asText());
    assertTrue(order(service.state()).path("customerId").asText().isBlank());
    save(draft(), sales);
    var next = draft(); order(next).put("paid", 1).put("method", "银行转账");
    assertThrows(ApiException.class, () -> save(next, admin));
  }
  @Test void orphanLegacyOrderRequiresExplicitSelectionBeforePayment() {
    order(stored).remove("customerId"); order(stored).put("customer", "旧系统客户");
    save(draft(), sales); // Other business operations must remain available.
    var next = draft(); order(next).put("customerId", "c1");
    var saved = save(next, admin);
    assertEquals("c1", order(saved).path("customerId").asText());
    assertEquals("客户甲", order(saved).path("customer").asText());
  }
  @Test void paidLegacyAssociationCanBeRepairedExplicitlyWithoutChangingMoney() throws Exception {
    order(stored).remove("customerId"); order(stored).put("customer", "旧系统客户").put("paid", 10);
    history.add(json("""
      {"id":"r1","order":"o1","customer":"旧系统客户","amount":10,"type":"销售订单","method":"银行转账"}
      """));
    var next = draft(); order(next).put("customerId", "c1");
    save(next, admin);
    var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).receipt(capture.capture());assertEquals("c1", capture.getValue().path("customerId").asText());
  }

  @Test void amendmentRefundLedgerDoesNotBlockOrdinarySaveOrChangePrepaymentBalance() throws Exception {
    history.add(json("{\"id\":\"diff-1\",\"order\":\"o1\",\"customerId\":\"c1\",\"amount\":-5,\"type\":\"订单变更\",\"amendmentId\":\"BG-1\",\"method\":\"银行转账\"}"));
    var result=save(draft(),admin);
    assertEquals(0,result.path("customers").get(0).path("balance").asInt());
    assertEquals(100,result.path("orders").get(0).path("total").asInt());
  }
  @Test void ordinaryNegativeReceiptIsStillRejected() throws Exception {
    history.add(json("{\"id\":\"bad\",\"order\":\"o1\",\"customerId\":\"c1\",\"amount\":-5,\"type\":\"销售订单\",\"method\":\"银行转账\"}"));
    assertThrows(ApiException.class,()->save(draft(),admin));
  }

  @Test void restoreRejectsOlderShipmentAndMissingDifferenceReceiptBeforeWriting() throws Exception {
    var shipment=mapper.createObjectNode().put("orderId","o1").put("soId","CRM-new");
    shipment.set("snapshot",order(stored).deepCopy());
    shipment.putObject("amendment").putObject("settlement").put("id","diff-1");
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    when(db.parse(anyString())).thenAnswer(i->mapper.readTree((String)i.getArgument(0)));
    var backup=mapper.createObjectNode().put("version",1);backup.set("state",draft());backup.putArray("receipts");
    backup.putArray("jstShipments").add(shipment.deepCopy().put("soId","CRM-old"));
    assertThrows(ApiException.class,()->service.restore(backup,admin));
    backup.putArray("jstShipments").add(shipment);
    assertThrows(ApiException.class,()->service.restore(backup,admin));
    verify(db,never()).state(any(JsonNode.class));verify(db,never()).receipt(any());
  }
  @Test void effectiveAndPendingShipmentProductsCannotBeDeleted() throws Exception {
    ((ArrayNode)stored.path("products")).addObject().put("id","p2").put("name","变更新商品");
    var shipment=json("{\"orderId\":\"o1\",\"effectiveOrder\":{\"items\":[{\"product\":\"p2\"}]}}");
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    when(db.parse(anyString())).thenAnswer(i->mapper.readTree((String)i.getArgument(0)));
    ((ObjectNode)shipment).set("snapshot",order(stored).deepCopy());
    when(db.rows("SELECT payload FROM jst_shipments")).thenReturn(List.of(Map.of("payload",shipment.toString())));
    var next=draft();((ArrayNode)next.path("products")).remove(1);
    var error=assertThrows(ApiException.class,()->save(next,admin));
    assertEquals("该商品已被订单使用，不能删除",error.getMessage());
  }

}
