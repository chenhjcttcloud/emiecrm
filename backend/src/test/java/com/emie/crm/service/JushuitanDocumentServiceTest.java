package com.emie.crm.service;

import static com.emie.crm.service.JushuitanDocumentService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class JushuitanDocumentServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CrmRepository db = mock(CrmRepository.class);
  private final JushuitanClient client = mock(JushuitanClient.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final JushuitanDocumentService service = new JushuitanDocumentService(db, client, transactions, "production");
  private final Actor actor = new Actor("sales", "销售", Set.of("business.read", "business.write"));

  private JsonNode json(String raw) throws Exception {
    return mapper.readTree(raw);
  }

  private void orderExists() throws Exception {
    var state = json("""
        {"orders":[{"id":"crm-1","type":"销售订单"}]}
        """);
    when(db.state(false)).thenReturn(state);
    when(db.state(true)).thenReturn(state);
  }

  @Test void queriesKnownSplitOrdersAndKeepsIndividualPermissionFailures() throws Exception {
    orderExists();
    String shipment = """
        {"environment":"production","oId":9007199254740993,"soId":"online-1","request":{"shop_id":7},
         "lastExternalObservation":{"orders":[
           {"shop_id":7,"so_id":"online-1","o_id":9007199254740994},
           {"shop_id":7,"so_id":"other","o_id":9},
           {"shop_id":8,"so_id":"online-1","o_id":10}]}}
        """;
    when(db.rows("SELECT payload FROM jst_shipments WHERE order_id=?", "crm-1"))
        .thenReturn(List.of(Map.of("payload", shipment)));
    when(db.parse(shipment)).thenReturn(json(shipment));
    when(db.rows(PURCHASES_BY_ORDER, "production", "crm-1")).thenReturn(List.of(Map.of("po_id", "31")));

    var allowedOutbound = json("""
        {"code":0,"data":{"datas":[
          {"shop_id":7,"o_id":9007199254740994,"io_id":15,"status":"Confirmed","io_date":"2026-11-01",
           "warehouse":"A仓","receiver_name":"Do not return","items":[{"sku_id":"A","name":"商品","qty":2}]},
          {"shop_id":8,"o_id":9007199254740993,"io_id":16},
          {"shop_id":7,"o_id":9,"io_id":17}]}}
        """);
    when(client.query(eq(OUTBOUND), anyMap())).thenReturn(allowedOutbound);
    when(client.query(eq(RETURNS), anyMap())).thenReturn(json("""
        {"code":190,"msg":"sensitive vendor message"}
        """));
    when(client.query(eq(PURCHASE), anyMap())).thenReturn(json("""
        {"code":0,"data":{"datas":[{"po_id":31,"seller":"供应商","source_o_id":7,"items":[{"sku_id":"A","qty":8}]},
                                 {"po_id":32,"seller":"other"}]}}
        """));
    when(client.query(eq(PURCHASE_IN), anyMap())).thenReturn(json("""
        {"code":190,"msg":"sensitive vendor message"}
        """));

    var result = service.get("crm-1", actor);
    assertEquals("9007199254740993", result.salesOrder().id());
    assertEquals("shipment", result.salesOrder().source());
    assertEquals(List.of("15"), result.outbound().rows().stream().map(Document::id).toList());
    assertEquals("商品", result.outbound().rows().getFirst().items().getFirst().name());
    assertTrue(result.returns().error().contains("未开通"));
    assertEquals(List.of("31"), result.purchases().stream().map(Purchase::id).toList());
    assertEquals("7", result.purchases().getFirst().sourceOrderId());
    assertTrue(result.purchases().getFirst().receipts().error().contains("未开通"));
    assertFalse(mapper.writeValueAsString(result).contains("sensitive"));
    assertFalse(mapper.writeValueAsString(result).contains("Do not return"));
    verify(client).query(RETURNS, Map.of("shop_id", "7", "o_ids", List.of("9007199254740993", "9007199254740994"),
        "page_index", 1, "page_size", 50));
  }

  @Test void exactSaleVerificationRejectsMismatchedShopWithoutWriting() throws Exception {
    orderExists();
    when(client.query(eq(SALES), anyMap())).thenReturn(json("""
        {"code":0,"data":{"orders":[{"shop_id":8,"o_id":12,"so_id":"x"}]}}
        """));
    var body = json("""
        {"shopId":"7","oId":"12"}
        """);

    assertThrows(ApiException.class, () -> service.linkSale("crm-1", body, actor));

    verify(client).query(SALES, Map.of("shop_id", 7L, "o_ids", List.of(12L), "page_index", 1, "page_size", 50));
    verify(client).query(SALES, Map.of("shop_id", 7L, "o_ids", List.of(12L), "archive", true, "page_index", 1, "page_size", 50));
    verify(db, never()).jdbc();
  }

  @Test void exactPurchaseVerificationRejectsMismatchedNumberWithoutWriting() throws Exception {
    orderExists();
    when(client.query(eq(PURCHASE), anyMap())).thenReturn(json("""
        {"code":0,"data":{"datas":[{"po_id":32}]}}
        """));
    var body = json("""
        {"poId":"31"}
        """);

    assertThrows(ApiException.class, () -> service.linkPurchase("crm-1", body, actor));

    verify(client).query(PURCHASE, Map.of("po_ids", List.of("31"), "page_index", 1, "page_size", 50));
    verify(db, never()).jdbc();
  }

  @Test void settlementVerificationBatchesByShopAndChecksShipmentPeriod() throws Exception {
    var current = Map.of("shop_id", 7L, "o_ids", List.of(12L, 13L), "page_index", 1, "page_size", 50);
    var archive = Map.of("shop_id", 7L, "o_ids", List.of(13L), "archive", true, "page_index", 1, "page_size", 50);
    when(client.query(SALES, current)).thenReturn(json("""
        {"code":0,"data":{"orders":[
          {"shop_id":7,"o_id":12,"so_id":"online-12","status":"Sent","send_date":"2026-10-31 18:30:00","pay_amount":"12.30",
           "wms_co_id":4,"receiver_name":"Do not return","items":[{"sku_id":"A","name":"子单商品","qty":1}]},
          {"shop_id":8,"o_id":13,"so_id":"other","status":"Sent","send_date":"2026-10-01"}
        ]}}
        """));
    when(client.query(SALES, archive)).thenReturn(json("""
        {"code":0,"data":{"orders":[{"shop_id":7,"o_id":13,"so_id":"online-13","status":"Sent",
                                    "send_date":"2026-10-01T09:00:00","pay_amount":20.01}]}}
        """));

    var sales = service.verifySales(json("""
        [{"shopId":"7","oId":"12"},{"shopId":"7","oId":"13"}]
        """));

    assertEquals(List.of("12", "13"), sales.stream().map(Sale::id).toList());
    assertEquals("2026-10-31", sales.getFirst().shippedOn());
    assertEquals("4", sales.getFirst().warehouseId());
    assertEquals("A", sales.getFirst().items().getFirst().sku());
    assertEquals(new BigDecimal("32.31"), totalAmount(sales));
    assertFalse(mapper.writeValueAsString(sales).contains("Do not return"));
    requireSettlementPeriod(sales, json("""
        {"periodStart":"2026-10-01","periodEnd":"2026-10-31"}
        """));
    assertThrows(ApiException.class, () -> requireSettlementPeriod(sales, json("""
        {"periodStart":"2026-11-01","periodEnd":"2026-11-30"}
        """)));
  }

  @Test void paidSettlementRelinkRejectsStaleClientsAndRequiresFinanceForAmountNeutralChanges() throws Exception {
    var state = json("""
        {"orders":[{"id":"crm-1","type":"销售订单","source":"jushuitan-settlement","paid":"20.00",
                    "settlement":{"periodStart":"2026-10-01","periodEnd":"2026-10-31"}}]}
        """);
    when(db.state(false)).thenReturn(state);
    when(db.state(true)).thenReturn(state);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var persisted = """
        {"shippedOn":"2026-10-01","status":"Sent","amount":"20.00","items":[]}
        """;
    when(db.parse(persisted)).thenReturn(json(persisted));
    when(db.rows(SALES_BY_ORDER, "production", "crm-1")).thenReturn(List.of(
        Map.of("shop_id", "7", "o_id", "12", "so_id", "online-12", "sale_metadata", persisted)));
    var orders = json("""
        {"code":0,"data":{"orders":[{"shop_id":7,"o_id":13,"so_id":"online-13","status":"Sent",
                                    "send_date":"2026-10-04","pay_amount":20.00}]}}
        """);
    when(client.query(eq(SALES), anyMap())).thenReturn(orders);
    var stale = json("""
        {"previousSales":[],"sales":[{"shopId":"7","oId":"13"}],"customerConfirmed":true}
        """);
    var replace = json("""
        {"previousSales":[{"shopId":"7","oId":"12"}],"sales":[{"shopId":"7","oId":"13"}],"customerConfirmed":true}
        """);
    assertEquals(409, assertThrows(ApiException.class, () -> service.replaceSettlementSales("crm-1", stale, actor)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.replaceSettlementSales("crm-1", replace, actor)).status());
    verify(db, never()).jdbc();

    var finance = new Actor("finance", "财务", Set.of("business.read", "business.write", "payment.write"));
    var changedAmount = orders.deepCopy();
    ((ObjectNode) changedAmount.path("data").path("orders").get(0)).put("pay_amount", "20.01");
    when(client.query(eq(SALES), anyMap())).thenReturn(changedAmount);
    assertThrows(ApiException.class, () -> service.replaceSettlementSales("crm-1", replace, finance));
    verify(db, never()).jdbc();

    when(client.query(eq(SALES), anyMap())).thenReturn(orders);
    var jdbc = mock(JdbcTemplate.class);
    when(db.jdbc()).thenReturn(jdbc);
    when(db.tree(any())).thenAnswer(invocation -> mapper.valueToTree(invocation.getArgument(0)));
    when(db.encode(any())).thenAnswer(invocation -> mapper.writeValueAsString(invocation.getArgument(0)));
    when(db.object()).thenAnswer(invocation -> mapper.createObjectNode());
    service.replaceSettlementSales("crm-1", replace, finance);
    verify(jdbc).update("DELETE FROM jst_document_sales WHERE environment=? AND order_id=? AND o_id=?",
        "production", "crm-1", "12");
    verify(db).audit(eq("finance"), eq("更新聚水潭销售子单关联"),
        argThat(detail -> detail.path("before").get(0).path("id").asText().equals("12")
            && detail.path("after").get(0).path("id").asText().equals("13")));
  }

  @Test void pendingSettlementRelinkCannotSilentlyChangeTheFinancialBaseline() throws Exception {
    var state = json("""
        {"orders":[{"id":"crm-1","type":"销售订单","source":"jushuitan-settlement",
                    "status":"审批中","total":"20.00","paid":"0.00",
                    "settlement":{"periodStart":"2026-10-01","periodEnd":"2026-10-31","erpAmount":20.00}}]}
        """);
    when(db.state(false)).thenReturn(state);
    when(db.state(true)).thenReturn(state);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    String persisted = """
        {"shippedOn":"2026-10-01","status":"Sent","amount":"20.00","items":[]}
        """;
    when(db.parse(persisted)).thenReturn(json(persisted));
    when(db.rows(SALES_BY_ORDER, "production", "crm-1")).thenReturn(List.of(
        Map.of("shop_id", "7", "o_id", "12", "so_id", "online-12", "sale_metadata", persisted)));
    when(client.query(eq(SALES), anyMap())).thenReturn(json("""
        {"code":0,"data":{"orders":[{"shop_id":7,"o_id":13,"so_id":"online-13","status":"Sent",
                                    "send_date":"2026-10-04","pay_amount":21.00}]}}
        """));
    when(db.jdbc()).thenReturn(mock(JdbcTemplate.class));
    when(db.tree(any())).thenAnswer(invocation -> mapper.valueToTree(invocation.getArgument(0)));
    when(db.encode(any())).thenAnswer(invocation -> mapper.writeValueAsString(invocation.getArgument(0)));
    when(db.object()).thenAnswer(invocation -> mapper.createObjectNode());
    var replacement = json("""
        {"previousSales":[{"shopId":"7","oId":"12"}],"sales":[{"shopId":"7","oId":"13"}],"customerConfirmed":true}
        """);

    var error = assertThrows(ApiException.class, () -> service.replaceSettlementSales("crm-1", replacement, actor));

    assertTrue(error.getMessage().contains("合计一致"));
    verify(db, never()).jdbc();
    verify(db, never()).state(any(JsonNode.class));
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
    assertEquals("20.00", state.path("orders").get(0).path("total").asText());
  }

  @Test void settlementBackupCannotRemoveAnExistingChild() throws Exception {
    var state = json("""
        {"orders":[{"id":"crm-1","type":"销售订单","source":"jushuitan-settlement",
                    "settlement":{"periodStart":"2026-10-01","periodEnd":"2026-10-31"}}]}
        """);
    var orders = Map.of("crm-1", state.path("orders").get(0));
    var links = json("""
        {"sales":[{"environment":"production","orderId":"crm-1","shopId":"7","oId":"12","soId":"A",
                   "metadata":{"shippedOn":"2026-10-01","status":"Sent","amount":"20.00"}}],"purchases":[]}
        """);
    String persisted = "{\"shippedOn\":\"2026-10-01\",\"status\":\"Sent\",\"amount\":\"20.00\"}";
    when(db.parse(persisted)).thenReturn(json(persisted));
    when(db.rows(contains("sale_metadata AS metadata"))).thenReturn(List.of(
        Map.of("environment", "production", "orderId", "crm-1", "shopId", "7", "oId", "12", "soId", "A", "metadata", persisted),
        Map.of("environment", "production", "orderId", "crm-1", "shopId", "7", "oId", "13", "soId", "B", "metadata", persisted)));
    when(db.encode(any())).thenAnswer(invocation -> mapper.writeValueAsString(invocation.getArgument(0)));

    assertThrows(ApiException.class, () -> JushuitanDocumentBackup.restoreLinks(db, links, orders, List.of(), state));
    verify(db, never()).jdbc();
  }

  @Test void removingPurchaseWorksWithoutContactingGateway() throws Exception {
    orderExists();
    var jdbc = mock(JdbcTemplate.class);
    when(db.jdbc()).thenReturn(jdbc);
    when(db.object()).thenAnswer(invocation -> mapper.createObjectNode());
    when(db.rows(PURCHASES_BY_ORDER, "production", "crm-1")).thenReturn(List.of(Map.of("po_id", "31")));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    service.linkPurchase("crm-1", json("""
        {"poId":"31","remove":true}
        """), actor);

    verify(jdbc).update("DELETE FROM jst_document_purchases WHERE environment=? AND order_id=? AND po_id=?",
        "production", "crm-1", "31");
    verify(db).audit(eq("sales"), eq("解除聚水潭采购单关联"), any(JsonNode.class));
    verifyNoInteractions(client);
  }
}
