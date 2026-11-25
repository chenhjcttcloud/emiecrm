package com.emie.crm.service;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Actor reader = new Actor("reader", "销售观察员", Set.of("business.read"));
  private final Actor manager = new Actor("manager", "目标管理员", Set.of("dashboard.targets.manage"));
  private CrmRepository db;
  private JdbcTemplate jdbc;
  private SalesService service;
  private ObjectNode state;
  private List<JsonNode> receipts;
  private List<Map<String, Object>> shipments;
  private List<Map<String, Object>> links;
  private List<Map<String, Object>> targetRows;
  private List<Map<String, Object>> users;
  private List<Map<String, Object>> userDepartments;
  private List<Map<String, Object>> departments;
  private List<Map<String, Object>> scopeRows;
  private SalesTargetScopeService scopes;

  @BeforeEach void setup() {
    state = mapper.createObjectNode(); state.putArray("orders");
    state.putArray("customers").addObject().put("id", "c1").put("name", "客户甲").put("owner", "小王").put("balance", 0);
    state.putArray("products").addObject().put("id", "p1").put("name", "商品甲").put("code", "SKU-A")
        .put("model", "型号甲").put("cost", "999.99");
    receipts = new ArrayList<>(); shipments = new ArrayList<>(); links = new ArrayList<>();
    targetRows = new ArrayList<>(); users = new ArrayList<>();
    userDepartments = new ArrayList<>(); departments = new ArrayList<>();
    scopeRows = new ArrayList<>();
    db = mock(CrmRepository.class); jdbc = mock(JdbcTemplate.class);
    when(db.jdbc()).thenReturn(jdbc);
    when(db.state(false)).thenAnswer(call -> state.deepCopy());
    when(db.object()).thenAnswer(call -> mapper.createObjectNode());
    when(db.array()).thenAnswer(call -> mapper.createArrayNode());
    when(db.tree(any())).thenAnswer(call -> mapper.valueToTree(call.getArgument(0)));
    when(db.parse(anyString())).thenAnswer(call -> mapper.readTree((String)call.getArgument(0)));
    when(db.encode(any())).thenAnswer(call -> mapper.writeValueAsString(call.getArgument(0)));
    when(db.documents("receipts", false, 0)).thenAnswer(call -> List.copyOf(receipts));
    when(db.rows(anyString(), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.contains("FROM jst_shipments")) return List.copyOf(shipments);
      if (sql.contains("FROM jst_document_sales")) return List.copyOf(links);
      if (sql.contains("FROM sales_target_scope")) return List.copyOf(scopeRows);
      if (sql.contains("FROM sales_targets")) return List.copyOf(targetRows);
      if (sql.contains("LEFT JOIN departments")) return List.copyOf(userDepartments);
      if (sql.contains("FROM departments")) return List.copyOf(departments);
      if (sql.contains("FROM users")) return List.copyOf(users);
      return List.of();
    });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    scopes = new SalesTargetScopeService(db);
    service = new SalesService(db, new DashboardService(db, scopes), scopes);
  }

  private ObjectNode order(String id, String total, String paid) {
    var order = ((ArrayNode)state.path("orders")).addObject().put("id", id).put("type", "销售订单")
        .put("status", "已批准").put("customerId", "c1").put("customer", "客户甲").put("owner", "小王")
        .put("created", "2026-11-02 09:00:00").put("total", total).put("paid", paid);
    order.putArray("items"); return order;
  }
  private ObjectNode line(ObjectNode order, String quantity, String price) {
    return order.withArray("items").addObject().put("product", "p1").put("qty", quantity).put("price", price);
  }
  private ObjectNode receipt(String id, String order, String amount, String method, String time) {
    var row = mapper.createObjectNode().put("id", id).put("order", order).put("customerId", "c1")
        .put("customer", "客户甲").put("type", "销售订单").put("amount", amount).put("method", method)
        .put("time", time).put("operator", "operator");
    receipts.add(row); return row;
  }
  private JsonNode items() { return items(1, 100, "", "2026-11", "", ""); }
  private JsonNode items(int page, int pageSize, String q, String month, String owner, String customer) {
    return mapper.valueToTree(service.items(page, pageSize, q, month, owner, customer, reader));
  }
  private JsonNode targets() { return mapper.valueToTree(service.targets("2026-11", reader)); }
  private JsonNode receiptList(int page, int pageSize, String q, String month, String owner, String customer, String method) {
    return mapper.valueToTree(service.receipts(page, pageSize, q, month, owner, customer, method, reader));
  }

  @Test void splitReceiptsFilterByEachRealMethodAndArrivalMonthAndExposeTheirApprovalLine() {
    order("split-order","100","100");
    receipt("split-one","split-order","40","银行A","2026-11-25T01:00:00Z")
        .put("receivedOn","2026-10-31").put("approvalId","batch-split").put("paymentLineNo",1);
    receipt("split-two","split-order","60","银行B","2026-11-25T01:00:00Z")
        .put("receivedOn","2026-11-01").put("approvalId","batch-split").put("paymentLineNo",2);
    var october=receiptList(1,20,"","2026-10","","","银行A");
    money("40",october.path("summary").path("amount"));
    assertEquals("batch-split",october.path("rows").get(0).path("approvalId").asText());
    assertEquals(1,october.path("rows").get(0).path("paymentLineNo").asInt());
    money("60",receiptList(1,20,"","2026-11","","","银行B").path("summary").path("amount"));
    assertEquals(0,receiptList(1,20,"","2026-11","","","组合收款").path("total").asInt());
  }

  @Test void shippedRejectedOrCancelledOrdersRemainVisibleAndHistoricalCashStaysActual() {
    var order=order("rejected","100","25").put("status","已取消");line(order,"1","100");
    shipments.add(Map.of("order_id","rejected","payload","{\"environment\":\"sandbox\",\"status\":\"SHIPPED\",\"nativeStatus\":\"Sent\",\"oId\":123,\"creditFulfillment\":true,\"effectiveOrder\":{\"total\":100,\"paid\":100}}"));
    when(db.rows("SELECT payload FROM feishu_order_approvals ORDER BY order_id"))
        .thenReturn(List.of(Map.of("payload","{\"orderId\":\"rejected\",\"status\":\"REJECTED\"}")));
    var items=items();assertEquals(1,items.path("rows").size());var row=items.path("rows").get(0);
    assertEquals("已取消",row.path("status").asText());assertEquals("REJECTED",row.path("approvalStatus").asText());
    assertTrue(row.path("fulfillmentException").asBoolean());assertEquals("SHIPPED",row.path("erpStatus").asText());
    assertTrue(row.path("fulfillmentWarning").asText().contains("已驳回"));
    receipt("real-receipt","rejected","25","银行转账","2026-11-03");
    var cash=receiptList(1,20,"","2026-11","","","");money("25",cash.path("summary").path("amount"));
    assertEquals("部分回款",cash.path("rows").get(0).path("status").asText());assertTrue(cash.path("rows").get(0).path("fulfillmentException").asBoolean());
    verify(db,never()).state(any(JsonNode.class));verify(db,never()).receipt(any());
  }
  private void money(String expected, JsonNode actual) {
    assertFalse(actual.isMissingNode(), "Missing decimal field");
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())), actual.toString());
  }
  private Map<String,Object> target(String owner, String amount, long revision) {
    var row = new LinkedHashMap<String,Object>(); row.put("month_key", "2026-11"); row.put("owner", owner);
    row.put("amount", amount == null ? null : new BigDecimal(amount)); row.put("revision", revision);
    row.put("updated_at", "2026-11-20T01:00:00Z"); row.put("updated_by", "manager"); return row;
  }

  @Test void inactiveOrdersLeaveSalesItemsAndPerformanceButTheirRealReceiptsRemainVisible() {
    line(order("active", "10", "0"), "1", "10");
    var canceled = order("canceled", "20", "0").put("status", "已取消").put("owner", "已取消签单人");
    line(canceled, "2", "10");
    var deleted = order("deleted", "30", "0").put("deletedAt", "2026-11-23T00:00:00Z").put("owner", "已删除签单人");
    line(deleted, "3", "10");
    var details = items();
    assertEquals(1, details.path("total").asInt()); money("10", details.path("summary").path("amount"));
    assertEquals("active", details.path("rows").get(0).path("orderId").asText());
    assertEquals(List.of("小王"), mapper.convertValue(details.path("options").path("owners"), List.class));
    var goals = targets();
    assertEquals(1, goals.path("rows").size()); money("10", goals.path("rows").get(0).path("orderAmount"));

    receipt("old-cash", "canceled", "5", "银行转账", "2026-11-03T01:00:00Z");
    receipt("old-refund", "deleted", "-2", "银行转账", "2026-11-04T01:00:00Z").put("type", "订单变更");
    var financial = receiptList(1, 20, "", "2026-11", "", "", "");
    assertEquals(2, financial.path("total").asInt()); money("3", financial.path("summary").path("amount"));
    var financialOwners = mapper.convertValue(financial.path("options").path("owners"), List.class);
    assertTrue(financialOwners.contains("已取消签单人")); assertTrue(financialOwners.contains("已删除签单人"));
  }

  @Test void businessNumberSearchAndReceiptExportKeepInternalOrderLinksIntact() {
    var numbered = order("stable-internal-id", "20", "10").put("orderNumber", "2026-11-00000001");
    line(numbered, "2", "10");
    receipt("stable-receipt-id", "stable-internal-id", "10", "银行转账", "2026-11-03T01:00:00Z");
    var original = state.deepCopy();
    var byNumber = items(1, 20, "2026-11-00000001", "2026-11", "", "");
    assertEquals(1, byNumber.path("total").asInt());
    var row = byNumber.path("rows").get(0);
    assertEquals("stable-internal-id:0", row.path("id").asText());
    assertEquals("stable-internal-id", row.path("orderId").asText());
    assertEquals("2026-11-00000001", row.path("orderNumber").asText());
    assertEquals(1, items(1, 20, "stable-internal-id", "2026-11", "", "").path("total").asInt());
    var financial = receiptList(1, 20, "2026-11-00000001", "2026-11", "", "", "");
    assertEquals(1, financial.path("total").asInt());
    var exported = mapper.valueToTree(service.exportReceipts("2026-11-00000001", "2026-11", "", "", "", reader));
    var receiptRow = exported.path("rows").get(0);
    assertEquals("stable-receipt-id", receiptRow.path("id").asText());
    assertEquals("stable-internal-id", receiptRow.path("orderId").asText());
    assertEquals("2026-11-00000001", receiptRow.path("orderNumber").asText());
    assertEquals(original, state);
  }

  @Test void unnumberedAndOrphanReceiptsKeepTheirOriginalDisplayedReferences() {
    line(order("legacy-order", "20", "10"), "2", "10");
    receipt("legacy-receipt", "legacy-order", "10", "银行转账", "2026-11-03T01:00:00Z");
    receipt("orphan-receipt", "missing-order", "5", "银行转账", "2026-11-04T01:00:00Z");
    var rows = receiptList(1, 20, "", "2026-11", "", "", "").path("rows");
    for (var row : rows) assertEquals(row.path("orderId").asText(), row.path("orderNumber").asText());
    assertEquals("legacy-order", items().path("rows").get(0).path("orderNumber").asText());
  }

  @Test void receiptProductsPreferRecordedSnapshotsAndLabelReadOnlyHistoricalFallbacks() {
    line(order("source", "20", "10"), "2", "10");
    var frozen = receipt("snapshot", "source", "2", "银行转账", "2026-11-01T01:00:00Z");
    frozen.putArray("items").addObject().put("product", "p1").put("name", "登记时名称").put("sku", "OLD-SKU")
        .put("model", "登记时型号").put("qty", "1").put("price", "2.00");
    receipt("legacy", "source", "3", "银行转账", "2026-11-02T01:00:00Z");
    receipt("empty-snapshot", "source", "4", "银行转账", "2026-11-03T01:00:00Z").putArray("items");
    receipt("orphan", "missing", "-1", "银行转账", "2026-11-04T01:00:00Z").put("type", "订单变更");
    var before = receipts.stream().map(JsonNode::deepCopy).toList();
    var report = receiptList(1, 20, "", "2026-11", "", "", "");
    var rows = new HashMap<String, JsonNode>();
    report.path("rows").forEach(row -> rows.put(row.path("id").asText(), row));
    assertEquals("snapshot", rows.get("snapshot").path("itemsSource").asText());
    assertEquals("登记时名称", rows.get("snapshot").path("items").get(0).path("name").asText());
    assertEquals("OLD-SKU", rows.get("snapshot").path("items").get(0).path("sku").asText());
    assertEquals("order", rows.get("legacy").path("itemsSource").asText());
    assertEquals("商品甲", rows.get("legacy").path("items").get(0).path("name").asText());
    assertEquals("2", rows.get("legacy").path("items").get(0).path("qty").asText());
    assertEquals("snapshot", rows.get("empty-snapshot").path("itemsSource").asText());
    assertTrue(rows.get("empty-snapshot").path("items").isEmpty());
    assertEquals("unknown", rows.get("orphan").path("itemsSource").asText());
    assertTrue(rows.get("orphan").path("items").isEmpty());
    assertEquals(before, receipts); verify(db, never()).receipt(any());
  }

  @Test void productRowsExpandEverySalesStatusAndNeverInventHistoricalCostsFromTodaysCatalog() {
    var approved = order("approved", "20", "0"); line(approved, "2", "10");
    var draft = order("draft", "10", "0").put("status", "草稿"); line(draft, "1", "10");
    var advance = order("advance", "500", "500").put("type", "预收款订单"); line(advance, "50", "10");
    var result = items();
    assertEquals(2, result.path("rows").size());
    money("30", result.path("summary").path("amount"));
    money("3", result.path("summary").path("quantity"));
    assertTrue(result.path("summary").path("cost").isNull());
    assertTrue(result.path("summary").path("profit").isNull());
    for (var row : result.path("rows")) {
      assertEquals("商品甲", row.path("product").asText());
      assertEquals("SKU-A", row.path("code").asText());
      assertTrue(row.path("barcode").isNull()); assertTrue(row.path("listedOn").isNull());
      assertTrue(row.path("unitCost").isNull()); assertTrue(row.path("cost").isNull()); assertTrue(row.path("profit").isNull());
    }
  }

  @Test void unitCostSnapshotProducesLineCostAndProfitWithExactDecimalStrings() {
    line(order("cost-known", "10", "0"), "2", "5").putObject("costSnapshot").put("unitCost", "3.50");
    var result = items(); var row = result.path("rows").get(0);
    assertEquals("3.50", row.path("unitCost").asText());
    assertEquals("7.00", row.path("cost").asText());
    assertEquals("3.00", row.path("profit").asText());
    money("7.00", result.path("summary").path("cost"));
    money("3.00", result.path("summary").path("profit"));
  }

  @Test void sixDecimalQuantitiesUseFinalLineRemainderSoDetailSumsMatchTheOrderExactly() {
    var order = order("fractional", "1.00", "0");
    for (int index = 0; index < 3; index++) line(order, "0.333333", "1.00");
    var result = items();
    assertEquals(3, result.path("rows").size());
    money("0.999999", result.path("summary").path("quantity"));
    money("1.00", result.path("summary").path("amount"));
    var sum = BigDecimal.ZERO;
    for (var row : result.path("rows")) sum = sum.add(new BigDecimal(row.path("amount").asText()));
    assertEquals(0, new BigDecimal("1.00").compareTo(sum));
    assertEquals("0.34", result.path("rows").get(2).path("amount").asText());
  }

  @Test void ambiguousLegacyCostFieldsAreNeverAssumedToBeHistoricalUnitCosts() {
    line(order("bare-cost", "10", "0"), "2", "5").put("cost", "3.50");
    line(order("scalar-snapshot", "10", "0"), "2", "5").put("costSnapshot", "3.50");
    line(order("snapshot-cost", "10", "0"), "2", "5").putObject("costSnapshot").put("cost", "3.50");
    var report = items(); assertEquals(3, report.path("rows").size());
    for (var row : report.path("rows")) {
      assertTrue(row.path("unitCost").isNull()); assertTrue(row.path("cost").isNull()); assertTrue(row.path("profit").isNull());
    }
    assertTrue(report.path("summary").path("cost").isNull());
    assertTrue(report.path("summary").path("profit").isNull());
  }

  @Test void roundingRemainderNeverCreatesANegativeLastLine() {
    var order = order("tiny-lines", "0.02", "0");
    for (int index = 0; index < 4; index++) line(order, "0.5", "0.01");
    var result = items(); var sum = BigDecimal.ZERO;
    for (var row : result.path("rows")) {
      var value = new BigDecimal(row.path("amount").asText());
      assertTrue(value.signum() >= 0); sum = sum.add(value);
    }
    assertEquals(0, new BigDecimal("0.02").compareTo(sum));
  }

  @Test void effectiveItemsReplaceTheRawLinesOnlyInTheCurrentEnvironment() {
    line(order("changed", "10", "10"), "1", "10");
    shipments.add(Map.of("order_id", "changed", "payload", "{\"environment\":\"sandbox\",\"effectiveOrder\":{\"total\":\"20.00\",\"paid\":\"20.00\",\"items\":[{\"product\":\"p1\",\"qty\":\"4\",\"price\":\"5\"}]}}"));
    var row = items().path("rows").get(0);
    money("4", row.path("qty")); money("5", row.path("price")); money("20", row.path("amount"));
    shipments.clear();
    shipments.add(Map.of("order_id", "changed", "payload", "{\"environment\":\"production\",\"effectiveOrder\":{\"total\":20,\"items\":[{\"product\":\"p1\",\"qty\":4,\"price\":5}]}}"));
    money("10", items().path("summary").path("amount"));
  }

  @Test void creditShipmentSnapshotsCannotInventOrEraseRealReceiptStatus() {
    var current=order("credit","100","25");
    receipt("cash","credit","25","银行转账","2026-11-04T01:00:00Z");
    shipments.add(Map.of("order_id","credit","payload","{\"environment\":\"sandbox\",\"creditFulfillment\":true,\"effectiveOrder\":{\"total\":100,\"paid\":0}}"));
    assertEquals("部分回款",receiptList(1,20,"","2026-11","","","").path("rows").get(0).path("status").asText());
    money("25",targets().path("rows").get(0).path("settledAmount"));
    current.put("paid",100);
    assertEquals("已回款",receiptList(1,20,"","2026-11","","","").path("rows").get(0).path("status").asText());
    current.put("paid",0);shipments.clear();
    shipments.add(Map.of("order_id","credit","payload","{\"environment\":\"sandbox\",\"creditFulfillment\":true,\"effectiveOrder\":{\"total\":100,\"paid\":100}}"));
    assertEquals("待回款",receiptList(1,20,"","2026-11","","","").path("rows").get(0).path("status").asText());
    money("0",targets().path("rows").get(0).path("settledAmount"));
  }

  @Test void productFiltersAndPaginationKeepSummariesOverEveryMatchingRow() {
    var order = order("three-lines", "60", "0");
    line(order, "1", "10"); line(order, "2", "10"); line(order, "3", "10");
    var page = items(2, 1, "sku-a", "2026-11", "小王", "c1");
    assertEquals(1, page.path("rows").size());
    assertEquals(3, page.path("total").asInt());
    money("60", page.path("summary").path("amount"));
    money("6", page.path("summary").path("quantity"));
    assertTrue(items(1, 20, "不存在", "2026-11", "", "").path("rows").isEmpty());
    money("0", items(1, 20, "", "2026-10", "", "").path("summary").path("amount"));
  }

  @Test void customerNamesFollowStableIdsAndLegacyUnknownOrdersStayReadable() {
    ((ObjectNode)state.path("customers").get(0)).put("name", "客户改名后");
    ((ArrayNode)state.path("customers")).addObject().put("id", "c2").put("name", "客户甲").put("balance", 0);
    line(order("renamed", "10", "0"), "1", "10");
    var orphan = order("orphan", "20", "0").put("customer", "历史客户"); orphan.remove("customerId"); line(orphan, "2", "10");
    var rows = items().path("rows");
    assertEquals(2, rows.size());
    var filtered = items(1, 20, "", "2026-11", "", "c1").path("rows");
    assertEquals(1, filtered.size()); assertEquals("客户改名后", filtered.get(0).path("customer").asText());
    assertTrue(items(1, 20, "", "2026-11", "", "c2").path("rows").isEmpty());
  }

  @Test void invalidLinePrecisionIsExcludedAsAWholeOrderRatherThanPartiallyCounted() {
    var order = order("bad-line", "20", "0"); line(order, "1", "10"); line(order, "1.0000001", "10");
    var result = items();
    assertTrue(result.path("rows").isEmpty()); money("0", result.path("summary").path("amount"));
    assertFalse(result.path("warnings").isEmpty());
  }

  @Test void salesReadAndTargetWritePermissionsRemainSeparate() {
    var dashboardOnly = new Actor("dash", "看板", Set.of("dashboard.read"));
    assertEquals(403, assertThrows(ApiException.class, () -> service.items(1, 20, "", "2026-11", "", "", dashboardOnly)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.targets("2026-11", dashboardOnly)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.receipts(1, 20, "", "2026-11", "", "", "", dashboardOnly)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.saveTarget(mapper.createObjectNode(), reader)).status());
    verify(db, never()).state(anyBoolean());
  }

  @Test void invalidPagingFiltersAndTargetParametersFailBeforeAnyMutation() {
    assertThrows(ApiException.class, () -> items(0, 20, "", "2026-11", "", ""));
    assertThrows(ApiException.class, () -> items(1, 101, "", "2026-11", "", ""));
    assertThrows(ApiException.class, () -> items(1, 20, "x".repeat(201), "2026-11", "", ""));
    assertThrows(ApiException.class, () -> items(1, 20, "", "2026-13", "", ""));
    assertThrows(ApiException.class, () -> service.targets("bad", reader));
    var body = mapper.createObjectNode().put("month", "2026-11").put("owner", "小王").put("revision", 0);
    for (String value : List.of("-1", "0.001", "1000000000000", "NaN", "1e3")) {
      body.put("amount", value); assertThrows(ApiException.class, () -> service.saveTarget(body, manager));
    }
    verifyNoInteractions(jdbc);
  }

  @Test void personnelTargetsUseApprovedEffectiveSalesAndKeepZeroUnsetAndOverachievementDistinct() {
    order("approved", "150", "50");
    order("draft", "999", "0").put("status", "草稿");
    order("advance", "500", "500").put("type", "预收款订单");
    order("other", "100", "100").put("owner", "小李");
    shipments.add(Map.of("order_id", "other", "payload", "{\"environment\":\"sandbox\",\"effectiveOrder\":{\"total\":20,\"paid\":20}}"));
    targetRows.add(target("小王", "100", 1)); targetRows.add(target("小李", "0", 1));
    users.add(Map.of("owner", "没有订单的用户"));
    var report = targets();
    var byOwner = new HashMap<String,JsonNode>(); for (var row : report.path("rows")) byOwner.put(row.path("owner").asText(), row);
    var wang = byOwner.get("小王");
    money("150", wang.path("orderAmount")); money("50", wang.path("settledAmount"));
    money("0", wang.path("remaining")); money("150", wang.path("progress"));
    var li = byOwner.get("小李");
    money("20", li.path("orderAmount")); money("20", li.path("settledAmount"));
    money("0", li.path("amount")); assertTrue(li.path("progress").isNull());
    assertTrue(byOwner.get("没有订单的用户").path("amount").isNull());
    assertTrue(byOwner.get("没有订单的用户").path("remaining").isNull());
    assertTrue(byOwner.get("没有订单的用户").path("progress").isNull());
    var names = new ArrayList<>(byOwner.keySet()); Collections.sort(names);
    var actual = new ArrayList<String>(); report.path("rows").forEach(row -> actual.add(row.path("owner").asText()));
    assertEquals(names, actual);
  }

  @Test void personnelAchievementReusesSettlementPeriodAndErpDeduplication() {
    order("ordinary", "100", "50");
    order("historical", "900", "900").put("source", "jushuitan-settlement").putObject("settlement")
        .put("periodStart", "2026-10-01").put("periodEnd", "2026-10-31");
    order("duplicate", "300", "0").put("source", "jushuitan-settlement").putObject("settlement")
        .put("periodStart", "2026-11-01").put("periodEnd", "2026-11-30");
    shipments.add(Map.of("order_id", "ordinary", "payload", "{\"environment\":\"sandbox\",\"oId\":123,\"soId\":\"CRM-ordinary\",\"request\":{\"shop_id\":1}}"));
    links.add(Map.of("environment", "sandbox", "order_id", "duplicate", "shop_id", "1", "o_id", "123", "so_id", "CRM-ordinary"));
    var report = targets();
    money("100", report.path("rows").get(0).path("orderAmount"));
    money("50", report.path("rows").get(0).path("settledAmount"));
    assertFalse(report.path("warnings").isEmpty());
  }

  @Test void personnelTargetWritesStaySeparateFromCompanyTargetsAndIncludeAudit() {
    order("person", "10", "0");
    when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
      if (((String)call.getArgument(0)).startsWith("UPDATE sales_targets")) {
        targetRows.clear(); targetRows.add(target(call.getArgument(5), call.getArgument(1).toString(), 1));
      }
      return 1;
    });
    var body = mapper.createObjectNode().put("month", "2026-11").put("owner", "小王").put("amount", "0").put("revision", 0);
    var saved = mapper.valueToTree(service.saveTarget(body, manager));
    assertEquals("小王", saved.path("owner").asText()); money("0", saved.path("amount"));
    assertEquals(1, saved.path("revision").asInt());
    var detail = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).audit(eq("manager"), contains("小王"), detail.capture());
    assertEquals("SALES_TARGET_CHANGED", detail.getValue().path("type").asText());
    assertTrue(detail.getValue().path("before").path("amount").isNull());
    verify(jdbc, never()).update(contains("dashboard_targets"), any(Object[].class));
  }

  @Test void stalePersonnelTargetRevisionDoesNotOverwriteOrAuditAndUnknownOwnersAreRejected() {
    order("person", "10", "0"); targetRows.add(target("小王", "100", 3));
    var body = mapper.createObjectNode().put("month", "2026-11").put("owner", "小王").put("amount", "200").put("revision", 2);
    assertEquals(409, assertThrows(ApiException.class, () -> service.saveTarget(body, manager)).status());
    verify(jdbc, never()).update(startsWith("UPDATE sales_targets"), any(Object[].class));
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
    body.put("owner", "并不存在的签单人");
    assertEquals(400, assertThrows(ApiException.class, () -> service.saveTarget(body, manager)).status());
  }

  @Test void receiptSummaryIncludesAllMatchingSignedEntriesAndDoesNotDiscardAdvanceAllocations() {
    order("paid", "100", "100");
    receipt("cash", "paid", "100", "银行转账", "2026-11-02T01:00:00Z");
    receipt("allocation", "paid", "50", "预收款核销", "2026-11-03T01:00:00Z");
    receipt("refund", "paid", "-20", "银行转账", "2026-11-04T01:00:00Z").put("type", "订单变更");
    var report = receiptList(1, 1, "", "2026-11", "", "", "");
    assertEquals(3, report.path("total").asInt()); assertEquals(1, report.path("rows").size());
    money("130", report.path("summary").path("amount")); assertTrue(report.path("summary").path("tax").isNull());
    assertEquals("-20.00", report.path("rows").get(0).path("amount").asText());
    var allocation = receiptList(1, 20, "", "2026-11", "", "", "预收款核销");
    assertEquals(1, allocation.path("total").asInt()); money("50", allocation.path("summary").path("amount"));
    assertTrue(allocation.path("warnings").toString().contains("核销"));
  }

  @Test void receiptExportIncludesAllMatchingPagesUsingTheSameFiltersAndTotalsWithoutWriting() {
    order("paid", "200", "105");
    order("other-owner", "200", "0").put("owner", "小李");
    for (int i = 0; i < 105; i++) {
      receipt("matching-" + i, "paid", "1", "银行转账", "2026-12-02T01:00:00Z")
          .put("receivedOn", "2026-11-30").put("operator", "统一到账")
          .put("taxRate", "13%").put("tax", "0.13");
    }
    receipt("wrong-owner", "other-owner", "7", "银行转账", "2026-11-02T01:00:00Z").put("operator", "统一到账");
    receipt("wrong-customer", "paid", "8", "银行转账", "2026-11-02T01:00:00Z").put("operator", "统一到账").put("customerId", "c2");
    receipt("wrong-method", "paid", "9", "预收款核销", "2026-11-02T01:00:00Z").put("operator", "统一到账");
    receipt("wrong-month", "paid", "10", "银行转账", "2026-11-02T01:00:00Z").put("operator", "统一到账").put("receivedOn", "2026-10-31");
    receipt("wrong-query", "paid", "11", "银行转账", "2026-11-02T01:00:00Z");
    var beforeState = state.deepCopy();
    var beforeReceipts = receipts.stream().map(JsonNode::deepCopy).toList();

    var exported = mapper.valueToTree(service.exportReceipts("统一到账", "2026-11", "小王", "c1", "银行转账", reader));
    assertEquals(105, exported.path("rows").size()); assertEquals(105, exported.path("total").asInt());
    verify(db, times(1)).documents("receipts", false, 0);
    assertTrue(exported.path("rows").findValuesAsText("id").stream().allMatch(id -> id.startsWith("matching-")));
    money("105", exported.path("summary").path("amount")); money("13.65", exported.path("summary").path("tax"));
    var secondPage = receiptList(2, 20, "统一到账", "2026-11", "小王", "c1", "银行转账");
    assertEquals(20, secondPage.path("rows").size()); assertEquals(2, secondPage.path("page").asInt());
    assertEquals(exported.path("summary"), secondPage.path("summary"));
    assertEquals(exported.path("options"), secondPage.path("options"));
    assertEquals(exported.path("warnings"), secondPage.path("warnings"));
    assertEquals(beforeState, state); assertEquals(beforeReceipts, receipts);
    verify(db, never()).state(true); verify(db, never()).receipt(any());
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
  }

  @Test void receiptExportRequiresBusinessReadAndValidatesAllFiltersBeforeReading() {
    var dashboardOnly = new Actor("dash", "看板", Set.of("dashboard.read"));
    assertEquals(403, assertThrows(ApiException.class,
        () -> service.exportReceipts("", "2026-11", "", "", "", dashboardOnly)).status());
    assertThrows(ApiException.class, () -> service.exportReceipts("x".repeat(201), "", "", "", "", reader));
    assertThrows(ApiException.class, () -> service.exportReceipts("", "", "x".repeat(192), "", "", reader));
    assertThrows(ApiException.class, () -> service.exportReceipts("", "", "", "x".repeat(192), "", reader));
    assertThrows(ApiException.class, () -> service.exportReceipts("", "", "", "", "x".repeat(51), reader));
    assertThrows(ApiException.class, () -> service.exportReceipts("", "2026-13", "", "", "", reader));
    verify(db, never()).state(anyBoolean()); verify(db, never()).documents(anyString(), anyBoolean(), anyInt());
  }

  @Test void receiptExportWithNoMatchesKeepsAnEmptyReportAndUnknownTax() {
    receipt("cash", "missing", "5", "银行转账", "2026-11-02T01:00:00Z");
    var exported = mapper.valueToTree(service.exportReceipts("", "2026-12", "", "", "", reader));
    assertEquals(0, exported.path("total").asInt()); assertTrue(exported.path("rows").isEmpty());
    money("0", exported.path("summary").path("amount")); assertTrue(exported.path("summary").path("tax").isNull());
    assertTrue(exported.path("options").path("methods").toString().contains("银行转账"));
  }

  @Test void receiptMonthPrefersReceivedOnAndFallsBackToShanghaiRegistrationDateOnlyWhenAbsent() {
    order("paid", "100", "100");
    receipt("actual-nov", "paid", "10", "银行转账", "2026-12-02T01:00:00Z").put("receivedOn", "2026-11-30");
    receipt("actual-oct", "paid", "20", "银行转账", "2026-11-02T01:00:00Z").put("receivedOn", "2026-10-31");
    receipt("registered-nov", "paid", "30", "银行转账", "2026-10-31T16:00:00Z");
    var report = receiptList(1, 100, "", "2026-11", "", "", "");
    assertEquals(2, report.path("total").asInt()); money("40", report.path("summary").path("amount"));
    var basis = new HashMap<String,String>(); report.path("rows").forEach(row -> basis.put(row.path("id").asText(), row.path("dateBasis").asText()));
    assertEquals("receivedOn", basis.get("actual-nov")); assertEquals("time", basis.get("registered-nov"));
  }

  @Test void legacyReceiptIdentitySurvivesCustomerRenameAndOrphanReceiptsRemainFilterable() {
    ((ObjectNode)state.path("customers").get(0)).put("name", "客户改名后");
    ((ArrayNode)state.path("customers")).addObject().put("id", "c2").put("name", "客户甲").put("balance", 0);
    order("paid", "100", "100");
    receipt("legacy", "paid", "100", "银行转账", "2026-11-02T01:00:00Z").remove("customerId");
    receipt("explicit", "paid", "20", "银行转账", "2026-11-03T01:00:00Z").put("customerId", "c2");
    var orphan = receipt("orphan", "missing", "5", "银行转账", "2026-11-04T01:00:00Z").put("customer", "遗留客户");
    orphan.remove("customerId");
    var c1 = receiptList(1, 20, "", "2026-11", "", "c1", "");
    money("100", c1.path("summary").path("amount")); assertEquals("客户改名后", c1.path("rows").get(0).path("customer").asText());
    money("20", receiptList(1, 20, "", "2026-11", "", "c2", "").path("summary").path("amount"));
    var all = receiptList(1, 20, "", "2026-11", "", "", "");
    String legacyKey = null;
    for (var option : all.path("options").path("customers")) if ("遗留客户".equals(option.path("name").asText())) legacyKey = option.path("id").asText();
    assertNotNull(legacyKey);
    var filtered = receiptList(1, 20, "", "2026-11", "", legacyKey, "");
    assertEquals(1, filtered.path("total").asInt()); assertEquals("orphan", filtered.path("rows").get(0).path("id").asText());
    assertEquals("未关联订单", filtered.path("rows").get(0).path("status").asText());
    money("0", receiptList(1, 20, "", "2026-11", "小王", legacyKey, "").path("summary").path("amount"));
  }

  @Test void taxIsOnlyAggregatedWhenEveryMatchingEntryHasRecordedTaxAndRateStaysVerbatim() {
    order("paid", "100", "100");
    receipt("tax1", "paid", "100", "银行转账", "2026-11-02T01:00:00Z").put("taxRate", "13%").put("tax", "13.00");
    receipt("tax2", "paid", "-10", "银行转账", "2026-11-03T01:00:00Z").put("taxRate", "0.13").put("tax", "-1.00");
    var known = receiptList(1, 20, "", "2026-11", "", "", "");
    money("12", known.path("summary").path("tax"));
    assertEquals("0.13", known.path("rows").get(0).path("taxRate").asText());
    assertEquals("13%", known.path("rows").get(1).path("taxRate").asText());
    receipt("unknown", "paid", "20", "银行转账", "2026-11-04T01:00:00Z");
    var unknown = receiptList(1, 20, "", "2026-11", "", "", "");
    assertTrue(unknown.path("summary").path("tax").isNull());
    assertTrue(unknown.path("rows").get(0).path("taxRate").isNull());
    assertTrue(unknown.path("rows").get(0).path("tax").isNull());
  }

  @Test void invalidActualReceiptDatesAreNotSilentlyReplacedByRegistrationDates() {
    receipt("bad-date", "missing", "10", "银行转账", "2026-11-02T01:00:00Z").put("receivedOn", "not-a-date");
    var month = receiptList(1, 20, "", "2026-11", "", "", "");
    assertEquals(0, month.path("total").asInt()); assertFalse(month.path("warnings").isEmpty());
    assertEquals(1, receiptList(1, 20, "", "", "", "", "").path("total").asInt());
  }

  @Test void longLegacyCustomerNamesReceiveBoundedFilterKeysThatCanBeQueried() {
    String legacyName = "没有客户ID的历史长名称".repeat(30);
    var row = receipt("legacy-long", "missing", "10", "银行转账", "2026-11-02T01:00:00Z").put("customer", legacyName);
    row.remove("customerId");
    var report = receiptList(1, 20, "", "2026-11", "", "", "");
    String filterKey = null;
    for (var option : report.path("options").path("customers"))
      if (legacyName.equals(option.path("name").asText())) filterKey = option.path("id").asText();
    assertNotNull(filterKey); assertTrue(filterKey.length() <= 191);
    assertNotEquals("c1", filterKey);
    var filtered = receiptList(1, 20, "", "2026-11", "", filterKey, "");
    assertEquals(1, filtered.path("total").asInt()); money("10", filtered.path("summary").path("amount"));
    assertEquals(legacyName, filtered.path("rows").get(0).path("customer").asText());
  }

  @Test void salesWithoutASignerStayInCompanyTotalsButNeverCreateAnEmptyPersonnelTargetRow() {
    order("known-owner", "20", "10");
    order("missing-owner", "10", "5").put("owner", "");
    order("blank-owner", "30", "10").put("owner", "   ");
    var report = targets();
    assertEquals(1, report.path("rows").size());
    assertEquals("小王", report.path("rows").get(0).path("owner").asText());
    money("20", report.path("rows").get(0).path("orderAmount"));
    for (var owner : report.path("options").path("owners")) assertFalse(owner.asText().isBlank());
    assertFalse(report.path("warnings").isEmpty());
    var company = mapper.valueToTree(new DashboardService(db, scopes).sales("2026-11", "", "",
        new Actor("dashboard", "经营看板", Set.of("dashboard.read"))));
    money("60", company.path("summary").path("orderAmount"));
  }

  private Map<String, Object> accountDepartment(String owner, String id, String name) {
    var row = new LinkedHashMap<String, Object>();
    row.put("owner", owner); row.put("departmentId", id); row.put("department", name);
    return row;
  }

  private JsonNode ownerRow(JsonNode report, String owner) {
    for (var row : report.path("rows")) if (owner.equals(row.path("owner").asText())) return row;
    throw new AssertionError("Missing owner " + owner);
  }

  @Test void personnelTargetsExposeOnlyTheUniquelyMatchedCurrentDepartmentToBusinessReaders() {
    order("assigned", "21", "6"); targetRows.add(target("小王", "100", 4));
    users.add(Map.of("owner", "小王"));
    var account = accountDepartment("小王", "d1", "销售一部");
    account.put("email", "private@example.invalid"); account.put("phone", "private-phone");
    userDepartments.add(account); departments.add(Map.of("id", "d1", "name", "销售一部"));
    var report = targets(); var row = ownerRow(report, "小王");
    assertEquals("assigned", row.path("departmentStatus").asText());
    assertEquals("d1", row.path("departmentId").asText()); assertEquals("销售一部", row.path("department").asText());
    assertEquals("100.00", row.path("amount").asText()); assertEquals(4, row.path("revision").asInt());
    money("21", row.path("orderAmount")); money("6", row.path("settledAmount")); money("79", row.path("remaining"));
    assertEquals("小王", report.path("options").path("owners").get(0).asText());
    assertEquals("d1", report.path("options").path("departments").get(0).path("id").asText());
    assertFalse(report.toString().contains("private@example.invalid")); assertFalse(report.toString().contains("private-phone"));
    verify(db).rows(eq("SELECT COALESCE(p.display_name,u.name) AS owner,d.id AS departmentId,d.name AS department FROM users u LEFT JOIN user_profiles p ON p.name=u.name LEFT JOIN departments d ON d.id=p.department_id"));
    verifyNoInteractions(jdbc);
  }

  @Test void namesakesNeverReceiveAnArbitraryDepartmentEvenWhenTheyShareTheSameDepartment() {
    order("namesake", "20", "10"); targetRows.add(target("小王", "100", 2));
    users.add(Map.of("owner", "小王")); users.add(Map.of("owner", "小王"));
    userDepartments.add(accountDepartment("小王", "d1", "一部"));
    userDepartments.add(accountDepartment("小王", "d2", "二部"));
    var report = targets(); var ambiguous = ownerRow(report, "小王");
    assertEquals("ambiguous", ambiguous.path("departmentStatus").asText());
    assertTrue(ambiguous.path("departmentId").isNull()); assertTrue(ambiguous.path("department").isNull());
    assertEquals(1, report.path("rows").size()); assertEquals(1, report.path("options").path("owners").size());
    money("100", ambiguous.path("amount")); money("20", ambiguous.path("orderAmount"));
    userDepartments.set(1, accountDepartment("小王", "d1", "一部"));
    var sameDepartment = ownerRow(targets(), "小王");
    assertEquals("ambiguous", sameDepartment.path("departmentStatus").asText());
    assertTrue(sameDepartment.path("departmentId").isNull()); assertTrue(sameDepartment.path("department").isNull());
    assertEquals(2, sameDepartment.path("revision").asInt());
  }

  @Test void unassignedAccountsAndUnmatchedHistoricalSignersAreKeptDistinct() {
    order("historic", "10", "0").put("owner", "历史签单人");
    users.add(Map.of("owner", "无部门账户")); users.add(Map.of("owner", "无资料账户"));
    users.add(Map.of("owner", "部门关联已失效"));
    userDepartments.add(accountDepartment("无部门账户", null, null));
    userDepartments.add(accountDepartment("无资料账户", null, null));
    userDepartments.add(accountDepartment("部门关联已失效", null, null));
    var report = targets();
    assertEquals("unmatched", ownerRow(report, "历史签单人").path("departmentStatus").asText());
    for (String name : List.of("无部门账户", "无资料账户", "部门关联已失效")) {
      var row = ownerRow(report, name);
      assertEquals("unassigned", row.path("departmentStatus").asText());
      assertTrue(row.path("departmentId").isNull()); assertTrue(row.path("department").isNull());
    }
    assertTrue(report.path("options").path("departments").isEmpty());
  }

  @Test void disabledDepartmentsRemainVisibleAsCurrentAffiliationsAndFilterOptions() {
    order("disabled-department", "20", "10");
    userDepartments.add(accountDepartment("小王", "disabled", "已停用部门"));
    departments.add(Map.of("id", "disabled", "name", "已停用部门", "status", "停用"));
    var report = targets(); var row = ownerRow(report, "小王");
    assertEquals("assigned", row.path("departmentStatus").asText());
    assertEquals("disabled", row.path("departmentId").asText()); assertEquals("已停用部门", row.path("department").asText());
    assertEquals("disabled", report.path("options").path("departments").get(0).path("id").asText());
    assertEquals("已停用部门", report.path("options").path("departments").get(0).path("name").asText());
    assertEquals(2, report.path("options").path("departments").get(0).size());
    verify(db).rows(eq("SELECT id,name FROM departments ORDER BY name,id"));
  }

  @Test void departmentMovesAndRenamesAreResolvedFreshWithoutMovingTargetsBetweenOwners() {
    order("moved", "20", "10"); targetRows.add(target("小王", "100", 8));
    userDepartments.add(accountDepartment("小王", "d1", "原部门"));
    assertEquals("d1", ownerRow(targets(), "小王").path("departmentId").asText());
    userDepartments.set(0, accountDepartment("小王", "d2", "现部门"));
    var moved = ownerRow(targets(), "小王");
    assertEquals("d2", moved.path("departmentId").asText()); assertEquals("现部门", moved.path("department").asText());
    assertEquals("小王", moved.path("owner").asText()); assertEquals(8, moved.path("revision").asInt());
    money("100", moved.path("amount")); money("20", moved.path("orderAmount"));
    userDepartments.set(0, accountDepartment("改名后的人员", "d2", "现部门"));
    var historicalName = ownerRow(targets(), "小王");
    assertEquals("unmatched", historicalName.path("departmentStatus").asText());
    assertTrue(historicalName.path("departmentId").isNull()); money("100", historicalName.path("amount"));
    verifyNoInteractions(jdbc);
  }

  @Test void departmentMatchingUsesExactOwnerStringsRatherThanCaseFoldingOrAliases() {
    order("upper", "10", "0").put("owner", "ALICE");
    order("lower", "20", "0").put("owner", "alice");
    userDepartments.add(accountDepartment("ALICE", "upper-dept", "大写账户部门"));
    userDepartments.add(accountDepartment("alice", "lower-dept", "小写账户部门"));
    var report = targets();
    assertEquals("upper-dept", ownerRow(report, "ALICE").path("departmentId").asText());
    assertEquals("lower-dept", ownerRow(report, "alice").path("departmentId").asText());
    assertEquals("assigned", ownerRow(report, "ALICE").path("departmentStatus").asText());
    assertEquals("assigned", ownerRow(report, "alice").path("departmentStatus").asText());
    assertEquals(2, report.path("rows").size());
  }

  @Test void departmentMetadataStillRequiresBusinessReadBeforeAnyDirectoryQuery() {
    clearInvocations(db, jdbc);
    var dashboardOnly = new Actor("dashboard", "看板观察员", Set.of("dashboard.read"));
    assertEquals(403, assertThrows(ApiException.class, () -> service.targets("2026-11", dashboardOnly)).status());
    verifyNoInteractions(db, jdbc);
  }

  private void selectedScope(List<String> ids) {
    scopeRows.clear();
    scopeRows.add(Map.of("mode", "selected", "department_ids", mapper.valueToTree(ids).toString(), "revision", 2L));
  }

  @Test void selectedDepartmentScopeFiltersPersonnelAndOwnerOptionsWithoutDeletingTargets() {
    order("selected", "21", "6"); order("hidden", "30", "10").put("owner", "小李");
    targetRows.add(target("小王", "100", 3)); targetRows.add(target("小李", "200", 4));
    userDepartments.add(accountDepartment("小王", "d1", "一部"));
    userDepartments.add(accountDepartment("小李", "d2", "二部"));
    departments.add(Map.of("id", "d1", "name", "一部")); departments.add(Map.of("id", "d2", "name", "二部"));
    selectedScope(List.of("d1"));
    var report = targets();
    assertEquals(1, report.path("rows").size()); assertEquals("小王", report.path("rows").get(0).path("owner").asText());
    assertEquals(1, report.path("options").path("owners").size());
    assertEquals("d1", report.path("options").path("departments").get(0).path("id").asText());
    assertEquals(1, report.path("options").path("departments").size());
    assertEquals("selected", report.path("scope").path("mode").asText());
    money("100", report.path("rows").get(0).path("amount"));
    assertEquals(2, targetRows.size()); assertEquals("200", targetRows.get(1).get("amount").toString());
    scopeRows.clear();
    assertEquals(2, targets().path("rows").size());
    verifyNoInteractions(jdbc);
  }

  @Test void emptyAndUncertainScopesExcludePersonnelUntilTheirCurrentAffiliationIsResolved() {
    order("known", "20", "10"); targetRows.add(target("小王", "100", 1));
    departments.add(Map.of("id", "d1", "name", "一部"));
    userDepartments.add(accountDepartment("小王", "d1", "一部"));
    selectedScope(List.of());
    assertTrue(targets().path("rows").isEmpty()); assertTrue(targets().path("options").path("owners").isEmpty());
    selectedScope(List.of("d1")); assertEquals(1, targets().path("rows").size());
    userDepartments.add(accountDepartment("小王", "d1", "一部")); assertTrue(targets().path("rows").isEmpty());
    userDepartments.clear(); assertTrue(targets().path("rows").isEmpty());
    userDepartments.add(accountDepartment("小王", null, null)); assertTrue(targets().path("rows").isEmpty());
    assertEquals(1, targetRows.size());
  }

  @Test void hiddenOwnerCannotBypassScopeByPostingAnExistingTargetDirectly() {
    order("hidden", "20", "0"); targetRows.add(target("小王", "100", 1));
    userDepartments.add(accountDepartment("小王", "d2", "二部"));
    departments.add(Map.of("id", "d1", "name", "一部")); departments.add(Map.of("id", "d2", "name", "二部"));
    selectedScope(List.of("d1"));
    var body = mapper.createObjectNode().put("month", "2026-11").put("owner", "小王").put("amount", "200").put("revision", 1);
    var failure = assertThrows(ApiException.class, () -> service.saveTarget(body, manager));
    assertEquals(409, failure.status()); assertTrue(failure.getMessage().contains("统计范围"));
    verify(db).rows(contains("FROM sales_target_scope WHERE id=1 FOR SHARE"));
    verify(jdbc, never()).update(contains("INTO sales_targets("), any(Object[].class));
    verify(jdbc, never()).update(startsWith("UPDATE sales_targets"), any(Object[].class));
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
  }
}
