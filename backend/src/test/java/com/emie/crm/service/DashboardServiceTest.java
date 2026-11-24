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

class DashboardServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Actor reader = new Actor("reader", "看板观察员", Set.of("dashboard.read"));
  private final Actor manager = new Actor("manager", "目标管理员", Set.of("dashboard.targets.manage"));
  private CrmRepository db;
  private JdbcTemplate jdbc;
  private DashboardService service;
  private ObjectNode state;
  private List<JsonNode> receipts;
  private List<Map<String, Object>> shipments;
  private List<Map<String, Object>> links;
  private List<Map<String, Object>> targetRows;
  private List<Map<String, Object>> personnelTargetRows;
  private List<Map<String, Object>> scopeRows;
  private List<Map<String, Object>> scopeAccounts;
  private List<Map<String, Object>> scopeDepartments;

  @BeforeEach void setup() {
    state = mapper.createObjectNode();
    state.putArray("orders");
    state.putArray("customers").addObject().put("id", "c1").put("name", "客户甲").put("owner", "小王").put("balance", 0);
    receipts = new ArrayList<>();
    shipments = new ArrayList<>();
    links = new ArrayList<>();
    targetRows = new ArrayList<>();
    personnelTargetRows = new ArrayList<>();
    scopeRows = new ArrayList<>(); scopeAccounts = new ArrayList<>(); scopeDepartments = new ArrayList<>();
    db = mock(CrmRepository.class);
    jdbc = mock(JdbcTemplate.class);
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
      if (sql.contains("LEFT JOIN departments")) return List.copyOf(scopeAccounts);
      if (sql.contains("FROM departments")) return List.copyOf(scopeDepartments);
      if (sql.contains("FROM dashboard_targets") || sql.contains("FROM sales_targets")) {
        var rows = sql.contains("FROM dashboard_targets") ? targetRows : personnelTargetRows;
        String start = call.getArgument(1);
        String end = sql.contains("month_key>=?") ? call.getArgument(2) : start;
        return rows.stream().filter(row -> row.get("month_key").toString().compareTo(start) >= 0
            && row.get("month_key").toString().compareTo(end) <= 0).toList();
      }
      return List.of();
    });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    service = new DashboardService(db, new SalesTargetScopeService(db));
  }

  private ObjectNode order(String id, String created, String total, String paid) {
    return ((ArrayNode)state.path("orders")).addObject().put("id", id).put("type", "销售订单")
        .put("status", "已批准").put("customerId", "c1").put("customer", "客户甲").put("owner", "小王")
        .put("created", created).put("total", total).put("paid", paid);
  }

  private ObjectNode receipt(String id, String order, String type, String amount, String method, String time) {
    var receipt = mapper.createObjectNode().put("id", id).put("order", order).put("customerId", "c1")
        .put("customer", "客户甲").put("type", type).put("amount", amount).put("method", method).put("time", time);
    receipts.add(receipt);
    return receipt;
  }

  private JsonNode sales() { return sales("2026-11", "", ""); }
  private JsonNode sales(String month, String owner, String customerId) {
    return mapper.valueToTree(service.sales(month, owner, customerId, reader));
  }
  private void money(String expected, JsonNode actual) {
    assertFalse(actual.isMissingNode(), "Missing monetary field");
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())), actual.toString());
  }

  @Test void unapprovedFulfillmentIsVisibleSeparatelyWithoutInflatingApprovedSalesOrCash() {
    order("approved", "2026-11-02", "50", "0");
    order("rejected", "2026-11-03", "12200", "0").put("status","草稿");
    order("pending", "2026-11-04", "80", "20").put("status","审批中");
    order("normal-parallel", "2026-11-04", "500", "0").put("status","审批中");
    order("cancelled", "2026-11-05", "90", "0").put("status","已取消");
    order("wrong-environment", "2026-11-06", "999", "0").put("status","草稿");
    for(String id:List.of("rejected","pending","cancelled"))shipments.add(Map.of("order_id",id,"payload",
        "{\"environment\":\"sandbox\",\"status\":\"SHIPPED\",\"oId\":123,\"nativeStatus\":\"Sent\",\"creditFulfillment\":true,\"effectiveOrder\":{\"paid\":12200}}"));
    shipments.add(Map.of("order_id","wrong-environment","payload","{\"environment\":\"production\",\"status\":\"SHIPPED\",\"oId\":456}"));
    shipments.add(Map.of("order_id","normal-parallel","payload","{\"environment\":\"sandbox\",\"status\":\"ACCEPTED\",\"nativeStatus\":\"WaitConfirm\",\"oId\":789,\"creditFulfillment\":true}"));
    when(db.rows("SELECT payload FROM feishu_order_approvals ORDER BY order_id")).thenReturn(List.of(
        Map.of("payload","{\"orderId\":\"rejected\",\"status\":\"REJECTED\"}"),
        Map.of("payload","{\"orderId\":\"pending\",\"status\":\"PENDING\"}"),
        Map.of("payload","{\"orderId\":\"normal-parallel\",\"status\":\"PENDING\"}"),
        Map.of("payload","{\"orderId\":\"cancelled\",\"status\":\"CANCELED\"}")));
    var result=sales();
    assertEquals(1,result.path("summary").path("orderCount").asInt());money("50",result.path("summary").path("orderAmount"));
    money("0",result.path("summary").path("settledAmount")); assertEquals(3,result.path("fulfillmentExceptions").path("count").asInt());
    var byId=new HashMap<String,JsonNode>();result.path("fulfillmentExceptions").path("rows").forEach(row->byId.put(row.path("orderId").asText(),row));
    assertFalse(byId.containsKey("wrong-environment")); assertFalse(byId.containsKey("normal-parallel"));
    money("0",byId.get("rejected").path("paid"));money("12200",byId.get("rejected").path("outstanding"));
    money("20",byId.get("pending").path("paid"));money("60",byId.get("pending").path("outstanding"));
    assertEquals("REJECTED",byId.get("rejected").path("approvalStatus").asText());assertTrue(byId.get("rejected").path("fulfillmentWarning").asText().contains("已驳回"));
    assertTrue(byId.get("pending").path("fulfillmentWarning").asText().contains("并行处理中"));
    assertTrue(byId.get("pending").path("fulfillmentWarning").asText().contains("待核对"));
    money("90",byId.get("cancelled").path("outstanding"));assertEquals("Sent",byId.get("cancelled").path("nativeStatus").asText());
    verify(db,never()).state(any(JsonNode.class));verify(db,never()).receipt(any());
  }

  @Test void onlyApprovedSalesEnterTheOrderCohortAndPartialPaymentsRemainPending() {
    order("paid", "2026-11-02 09:00:00", "100.01", "100.01");
    order("partial", "2026-11-03 09:00:00", "200.02", "50.01");
    order("draft", "2026-11-04 09:00:00", "900", "0").put("status", "草稿");
    order("review", "2026-11-04 09:00:00", "800", "0").put("status", "审批中");
    order("advance", "2026-11-04 09:00:00", "700", "700").put("type", "预收款订单");
    order("overseas", "2026-11-04 09:00:00", "600", "600").put("type", "海外提货单");
    var report = sales(); var summary = report.path("summary");
    assertEquals(2, summary.path("orderCount").asInt());
    assertEquals(1, summary.path("settledCount").asInt());
    assertEquals(1, summary.path("pendingCount").asInt());
    money("300.03", summary.path("orderAmount"));
    money("150.02", summary.path("settledAmount"));
    money("150.01", summary.path("outstandingAmount"));
    assertEquals(2, report.path("orders").size());
    assertEquals(30, report.path("daily").size());
    money("0", report.path("daily").get(0).path("amount"));
  }

  @Test void businessNumbersAreDisplayedWithoutChangingDashboardOrderOrReceiptLinks() {
    order("stable-id", "2026-11-02", "30", "0").put("orderNumber", "2026-11-00000001");
    order("advance-id", "2026-11-02", "20", "0").put("type", "预收款订单").put("orderNumber", "2026-11-00000002");
    receipt("cash-id", "advance-id", "预收款订单", "12", "银行转账", "2026-11-03T01:00:00Z");
    var original = state.deepCopy();
    var report = sales();
    var row = report.path("orders").get(0);
    assertEquals("stable-id", row.path("id").asText());
    assertEquals("2026-11-00000001", row.path("orderNumber").asText());
    var receipt = report.path("advanceReceipts").get(0);
    assertEquals("cash-id", receipt.path("id").asText());
    assertEquals("advance-id", receipt.path("order").asText());
    assertEquals("2026-11-00000002", receipt.path("orderNumber").asText());
    assertEquals(original, state);
  }

  @Test void invalidSettlementWarningsIdentifyTheBusinessNumber() {
    var settlement = order("hidden-internal-id", "2026-11-02", "30", "0")
        .put("source", "jushuitan-settlement").put("orderNumber", "2026-11-00000001");
    settlement.putObject("settlement").put("periodStart", "invalid").put("periodEnd", "2026-11-02");
    String warnings = sales().path("warnings").toString();
    assertTrue(warnings.contains("2026-11-00000001"));
    assertFalse(warnings.contains("hidden-internal-id"));
  }

  @Test void inactiveOrdersDoNotContributeSalesButHistoricalAdvanceCashIsPreserved() {
    order("active", "2026-11-02", "30", "0");
    order("canceled", "2026-11-02", "90", "0").put("status", "已取消").put("owner", "已取消无流水");
    order("deleted", "2026-11-02", "80", "0").put("deletedAt", "2026-11-23T00:00:00Z").put("owner", "已删除无流水");
    order("old-advance", "2026-11-02", "20", "0").put("type", "预收款订单").put("status", "已取消")
        .put("deletedAt", "2026-11-23T00:00:00Z").put("owner", "历史预收款签单人");
    receipt("real-cash", "old-advance", "预收款订单", "12", "银行转账", "2026-11-03T01:00:00Z");
    var report = sales();
    assertEquals(1, report.path("summary").path("orderCount").asInt());
    money("30", report.path("summary").path("orderAmount"));
    money("12", report.path("summary").path("advanceReceived"));
    assertEquals(1, report.path("advanceReceipts").size());
    var owners = mapper.convertValue(report.path("options").path("owners"), List.class);
    assertFalse(owners.contains("已取消无流水")); assertFalse(owners.contains("已删除无流水"));
    assertTrue(owners.contains("历史预收款签单人"));
    money("12", sales("2026-11", "历史预收款签单人", "").path("summary").path("advanceReceived"));
  }

  @Test void utcTimestampsAreGroupedByShanghaiDateAtBothMonthBoundaries() {
    order("november", "2026-10-31T16:00:00Z", "10", "0");
    order("october", "2026-10-31T15:59:59Z", "20", "0");
    order("december", "2026-11-30T16:00:00Z", "40", "0");
    var report = sales();
    money("10", report.path("summary").path("orderAmount"));
    assertEquals("2026-11-01", report.path("orders").get(0).path("date").asText());
    money("10", report.path("daily").get(0).path("amount"));
  }

  @Test void aggregateAmountsStayExactEvenAboveJavascriptSafeCents() {
    for (int index = 0; index < 99; index++)
      order("large-" + index, "2026-11-01", "999999999999.99", "0");
    var report = sales();
    assertTrue(report.path("summary").path("orderAmount").isTextual());
    assertEquals("98999999999999.01", report.path("summary").path("orderAmount").asText());
    assertEquals("98999999999999.01", report.path("daily").get(0).path("amount").asText());
    assertEquals("999999999999.99", report.path("orders").get(0).path("total").asText());
  }

  @Test void onlyTheSavedEffectiveOrderChangesTotalsAndPendingAmendmentsDoNot() {
    order("changed", "2026-11-04", "100", "100");
    order("pending", "2026-11-05", "80", "40");
    shipments.add(Map.of("order_id", "changed", "payload", "{\"environment\":\"sandbox\",\"effectiveOrder\":{\"total\":60,\"paid\":60}}"));
    shipments.add(Map.of("order_id", "pending", "payload", "{\"environment\":\"sandbox\",\"amendment\":{\"status\":\"AWAITING_FUNDS\",\"effectiveOrder\":{\"total\":20,\"paid\":20}}}"));
    var summary = sales().path("summary");
    money("140", summary.path("orderAmount"));
    money("100", summary.path("settledAmount"));
    money("40", summary.path("outstandingAmount"));
  }

  @Test void creditTenderNeverCountsAsCrmCashAndLaterRealReceiptsOverrideFrozenShipmentPaid() {
    var current=order("credit","2026-11-04","12200","0");
    shipments.add(Map.of("order_id","credit","payload","{\"environment\":\"sandbox\",\"creditFulfillment\":true,\"effectiveOrder\":{\"total\":12200,\"paid\":12200}}"));
    var summary=sales().path("summary");
    money("0",summary.path("settledAmount"));money("12200",summary.path("outstandingAmount"));
    assertEquals(0,summary.path("settledCount").asInt());
    current.put("paid",200);shipments.clear();
    shipments.add(Map.of("order_id","credit","payload","{\"environment\":\"sandbox\",\"creditFulfillment\":true,\"effectiveOrder\":{\"total\":12200,\"paid\":0}}"));
    summary=sales().path("summary");
    money("200",summary.path("settledAmount"));money("12000",summary.path("outstandingAmount"));
    money("200",sales().path("orders").get(0).path("paid"));
  }

  @Test void shipmentFromAnotherEnvironmentCannotOverrideTheCurrentBusinessOrder() {
    order("sale", "2026-11-04", "100", "50");
    shipments.add(Map.of("order_id", "sale", "payload", "{\"environment\":\"production\",\"effectiveOrder\":{\"total\":10,\"paid\":10}}"));
    money("100", sales().path("summary").path("orderAmount"));
    money("50", sales().path("summary").path("settledAmount"));
  }

  @Test void missingOrderAmountIsNotInventedAsZeroButLegacyMissingPaidDefaultsToZero() {
    order("missing-total", "2026-11-04", "100", "0").remove("total");
    order("legacy-unpaid", "2026-11-05", "20", "0").remove("paid");
    var report = sales();
    money("20", report.path("summary").path("orderAmount"));
    money("0", report.path("summary").path("settledAmount"));
    assertEquals(1, report.path("summary").path("orderCount").asInt());
    assertTrue(report.path("warnings").toString().contains("missing-total"));
  }

  @Test void cancellingShipmentDoesNotSilentlyInventARefundOrReduceSales() {
    order("cancelled-shipment", "2026-11-04", "100", "100");
    shipments.add(Map.of("order_id", "cancelled-shipment", "payload", "{\"environment\":\"sandbox\",\"status\":\"CANCELLED\"}"));
    var report = sales();
    money("100", report.path("summary").path("orderAmount"));
    money("100", report.path("summary").path("settledAmount"));
    assertFalse(report.path("warnings").isEmpty());
  }

  @Test void oneErpSaleCannotEnterTotalsThroughBothAnOrderAndASettlement() {
    order("ordinary", "2026-11-03", "100", "100");
    order("settlement", "2026-12-01", "300", "0").put("source", "jushuitan-settlement")
        .putObject("settlement").put("periodStart", "2026-11-01").put("periodEnd", "2026-11-30");
    shipments.add(Map.of("order_id", "ordinary", "payload", "{\"environment\":\"sandbox\",\"oId\":123,\"soId\":\"CRM-ordinary\",\"request\":{\"shop_id\":1}}"));
    links.add(Map.of("environment", "sandbox", "order_id", "settlement", "shop_id", "1", "o_id", "123", "so_id", "CRM-ordinary"));
    var report = sales();
    money("100", report.path("summary").path("orderAmount"));
    assertEquals(1, report.path("orders").size());
    assertTrue(report.path("warnings").toString().contains("settlement"));
  }

  @Test void historicalSettlementUsesItsPeriodAndSameMonthUsesPeriodEndForDailyChart() {
    order("historical", "2026-11-18", "900", "0").put("source", "jushuitan-settlement")
        .putObject("settlement").put("periodStart", "2026-10-01").put("periodEnd", "2026-10-31").put("cycle", "month");
    order("this-month", "2026-12-01", "120", "20").put("source", "jushuitan-settlement")
        .putObject("settlement").put("periodStart", "2026-11-01").put("periodEnd", "2026-11-30").put("cycle", "month");
    var report = sales();
    money("120", report.path("summary").path("orderAmount"));
    money("120", report.path("daily").get(29).path("amount"));
    assertEquals("this-month", report.path("orders").get(0).path("id").asText());
    assertFalse(report.path("warnings").isEmpty());
  }

  @Test void crossMonthSettlementAndMalformedDatesAreNotInventedOrEvenlyDistributed() {
    order("cross-month", "2026-11-18", "300", "0").put("source", "jushuitan-settlement")
        .putObject("settlement").put("periodStart", "2026-10-30").put("periodEnd", "2026-11-02").put("cycle", "day");
    order("bad-date", "not-a-date", "400", "0");
    var report = sales();
    money("0", report.path("summary").path("orderAmount"));
    assertFalse(report.path("warnings").isEmpty());
    assertTrue(report.path("orders").isEmpty());
  }

  @Test void advanceReceiptsUseSignedRegistrationAmountsAndNeverCountAdvanceAllocationAsNewCash() {
    order("pre", "2026-10-01", "100", "100").put("type", "预收款订单");
    order("sale", "2026-11-02", "100", "100");
    receipt("pre-receipt", "pre", "预收款订单", "100", "银行转账", "2026-11-01T01:00:00Z");
    receipt("allocation", "sale", "销售订单", "100", "预收款核销", "2026-11-02T01:00:00Z");
    receipt("refund", "pre", "预收款订单", "-10", "银行转账", "2026-11-03T01:00:00Z");
    receipt("prior", "pre", "预收款订单", "300", "银行转账", "2026-10-01T01:00:00Z");
    var report = sales();
    money("90", report.path("summary").path("advanceReceived"));
    money("0", report.path("summary").path("advanceBalance"));
    money("100", report.path("summary").path("settledAmount"));
    assertEquals(2, report.path("advanceReceipts").size());
  }

  @Test void ownerAndCustomerFiltersKeepDetailsAlignedAndDoNotCompareAgainstCompanyTarget() {
    order("mine", "2026-11-02", "100", "20");
    order("other-owner", "2026-11-03", "200", "100").put("owner", "小李");
    ((ArrayNode)state.path("customers")).addObject().put("id", "c2").put("name", "客户乙").put("balance", 50);
    order("other-customer", "2026-11-04", "400", "100").put("customerId", "c2").put("customer", "客户乙");
    ((ObjectNode)state.path("customers").get(0)).put("balance", 30);
    var filtered = sales("2026-11", "小王", "c1");
    money("100", filtered.path("summary").path("orderAmount"));
    assertEquals(1, filtered.path("orders").size());
    assertFalse(filtered.path("targetApplicable").asBoolean());
    assertTrue(filtered.path("summary").path("advanceBalance").isNull());
    money("30", sales("2026-11", "", "c1").path("summary").path("advanceBalance"));
    money("80", sales().path("summary").path("advanceBalance"));
  }

  @Test void historicalAdvanceReceiptUsesItsOrderIdentityWhenTheOldCustomerNameIsReused() {
    ((ObjectNode)state.path("customers").get(0)).put("name", "客户改名后");
    ((ArrayNode)state.path("customers")).addObject().put("id", "c2").put("name", "客户甲").put("balance", 0);
    order("pre", "2026-11-01", "100", "100").put("type", "预收款订单").put("customer", "客户改名后");
    receipt("legacy", "pre", "预收款订单", "100", "银行转账", "2026-11-02T01:00:00Z").remove("customerId");
    var originalCustomer = sales("2026-11", "", "c1");
    money("100", originalCustomer.path("summary").path("advanceReceived"));
    assertEquals("客户改名后", originalCustomer.path("advanceReceipts").get(0).path("customer").asText());
    money("0", sales("2026-11", "", "c2").path("summary").path("advanceReceived"));
    assertTrue(sales("2026-11", "", "c2").path("advanceReceipts").isEmpty());

    receipt("explicit", "pre", "预收款订单", "20", "银行转账", "2026-11-03T01:00:00Z").put("customerId", "c2");
    money("100", sales("2026-11", "", "c1").path("summary").path("advanceReceived"));
    var explicitCustomer = sales("2026-11", "", "c2");
    money("20", explicitCustomer.path("summary").path("advanceReceived"));
    assertEquals("客户甲", explicitCustomer.path("advanceReceipts").get(0).path("customer").asText());
  }

  @Test void orderMonthUsesCurrentCumulativeSettlementRatherThanOnlySameMonthReceipts() {
    order("october-sale", "2026-10-20", "100", "100");
    receipt("late-payment", "october-sale", "销售订单", "100", "银行转账", "2026-11-10T01:00:00Z");
    money("0", sales().path("summary").path("settledAmount"));
    money("100", sales("2026-10", "", "").path("summary").path("settledAmount"));
  }

  @Test void dashboardPermissionsAreIndependentOfExistingBusinessPermissions() {
    var business = new Actor("sales", "销售", Set.of("business.read", "system.manage"));
    assertEquals(403, assertThrows(ApiException.class, () -> service.sales("2026-11", "", "", business)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.targets(2026, reader)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.saveTarget(mapper.createObjectNode(), reader)).status());
    verify(db, never()).state(anyBoolean());
  }

  @Test void invalidMonthYearAndOversizedFilterAreRejected() {
    for (String month : List.of("2026-1", "2026-13", "2026-11-01", "anything"))
      assertEquals(400, assertThrows(ApiException.class, () -> sales(month, "", "")).status(), month);
    assertThrows(ApiException.class, () -> service.targets(0, manager));
    assertThrows(ApiException.class, () -> service.targets(10000, manager));
    assertThrows(ApiException.class, () -> sales("2026-11", "a".repeat(201), ""));
  }

  @Test void targetListIncludesEveryMonthAndDoesNotTurnUnsetTargetsIntoZero() {
    var report = mapper.valueToTree(service.targets(2026, manager));
    assertEquals(2026, report.path("year").asInt());
    assertEquals(12, report.path("targets").size());
    assertTrue(report.path("targets").get(0).path("amount").isNull());
    assertTrue(sales().path("target").path("amount").isNull());
    targetRows.add(target("2026-11", "0", 1));
    money("0", sales().path("target").path("amount"));
  }

  private Map<String, Object> target(String month, String amount, long revision) {
    return new LinkedHashMap<>(Map.of("month_key", month, "amount", new BigDecimal(amount), "revision", revision,
        "updated_at", "2026-11-19T01:00:00Z", "updated_by", "manager"));
  }

  private Map<String, Object> personnelTarget(String month, String owner, String amount) {
    var row = new LinkedHashMap<String, Object>();
    row.put("month_key", month); row.put("owner", owner);
    row.put("amount", amount == null ? null : new BigDecimal(amount));
    return row;
  }

  @Test void personnelTargetSumOverridesManualWithoutRewritingItsValueOrVersion() {
    targetRows.add(target("2026-11", "777", 4));
    personnelTargetRows.add(personnelTarget("2026-11", "甲", "0.10"));
    personnelTargetRows.add(personnelTarget("2026-11", "乙", "0.20"));
    personnelTargetRows.add(personnelTarget("2026-12", "其他月份", "999"));
    var goal = sales().path("target");
    assertEquals("0.30", goal.path("amount").asText());
    assertEquals("personnel", goal.path("source").asText());
    assertEquals(2, goal.path("personCount").asInt());
    assertEquals("777.00", goal.path("manualAmount").asText());
    assertEquals(4, goal.path("revision").asInt());
    assertEquals("2026-11-19T01:00:00Z", goal.path("updatedAt").asText());
    assertEquals("manager", goal.path("updatedBy").asText());
    assertFalse(goal.has("owners"));
    assertFalse(goal.toString().contains("其他月份"));
    verifyNoInteractions(jdbc);
    assertEquals(new BigDecimal("777"), targetRows.get(0).get("amount"));
  }

  @Test void personnelSumStaysExactAboveJavascriptSafeCentsAndUpdatesOnEveryRead() {
    for (int index = 0; index < 99; index++)
      personnelTargetRows.add(personnelTarget("2026-11", "签单人" + index, "999999999999.99"));
    assertEquals("98999999999999.01", sales().path("target").path("amount").asText());
    personnelTargetRows.set(0, personnelTarget("2026-11", "签单人0", "0.01"));
    var goal = sales().path("target");
    assertEquals("97999999999999.03", goal.path("amount").asText());
    assertTrue(goal.path("amount").isTextual());
    assertEquals(99, goal.path("personCount").asInt());
    verifyNoInteractions(jdbc);
  }

  @Test void explicitPersonnelZeroIsConfiguredButNullAndBlankOwnersCannotOverrideManualFallback() {
    targetRows.add(target("2026-11", "888", 3));
    personnelTargetRows.add(personnelTarget("2026-11", "未设置", null));
    personnelTargetRows.add(personnelTarget("2026-11", "", "999"));
    personnelTargetRows.add(personnelTarget("2026-11", " \u3000", "999"));
    var manual = sales().path("target");
    assertEquals("888.00", manual.path("amount").asText());
    assertEquals("manual", manual.path("source").asText());
    assertEquals(0, manual.path("personCount").asInt());
    personnelTargetRows.add(personnelTarget("2026-11", "明确零目标", "0"));
    var zero = sales().path("target");
    assertEquals("0.00", zero.path("amount").asText());
    assertEquals("personnel", zero.path("source").asText());
    assertEquals(1, zero.path("personCount").asInt());
    assertEquals("888.00", zero.path("manualAmount").asText());
  }

  @Test void annualAndMonthlyViewsUseTheSameEffectiveTargetAndOnlyTwoAnnualQueries() {
    targetRows.add(target("2026-11", "123", 5));
    targetRows.add(target("2026-10", "0", 2));
    personnelTargetRows.add(personnelTarget("2026-11", "一", "10.01"));
    personnelTargetRows.add(personnelTarget("2026-11", "二", "20.02"));
    personnelTargetRows.add(personnelTarget("2027-11", "别的年份", "1000"));
    var monthly = sales().path("target");
    clearInvocations(db);
    var report = mapper.valueToTree(service.targets(2026, manager));
    assertEquals(monthly, report.path("targets").get(10));
    assertEquals("manual", report.path("targets").get(9).path("source").asText());
    assertEquals("0.00", report.path("targets").get(9).path("amount").asText());
    assertEquals("unset", report.path("targets").get(0).path("source").asText());
    assertTrue(report.path("targets").get(0).path("manualAmount").isNull());
    verify(db, times(1)).rows(contains("FROM dashboard_targets"), any(Object[].class));
    verify(db, times(1)).rows(contains("FROM sales_targets"), any(Object[].class));
    verify(db).rows(contains("FROM sales_targets WHERE month_key>=? AND month_key<=?"), eq("2026-01"), eq("2026-12"));
    verifyNoInteractions(jdbc);
  }

  @Test void emptyTargetAndFilteringKeepGlobalTargetSourceAndApplicabilitySeparate() {
    var unset = sales().path("target");
    assertEquals("unset", unset.path("source").asText());
    assertEquals(0, unset.path("personCount").asInt());
    assertTrue(unset.path("amount").isNull());
    assertTrue(unset.path("manualAmount").isNull());
    personnelTargetRows.add(personnelTarget("2026-11", "甲", "100"));
    var filtered = sales("2026-11", "小王", "c1");
    assertEquals("100.00", filtered.path("target").path("amount").asText());
    assertEquals("personnel", filtered.path("target").path("source").asText());
    assertFalse(filtered.path("targetApplicable").asBoolean());
  }

  @Test void permissionsAreCheckedBeforePersonnelTargetsCanBeReadOrDisclosed() {
    personnelTargetRows.add(personnelTarget("2026-11", "私有人名", "100"));
    clearInvocations(db, jdbc);
    var denied = new Actor("business", "销售", Set.of("business.read"));
    assertEquals(403, assertThrows(ApiException.class, () -> service.sales("2026-11", "", "", denied)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.targets(2026, reader)).status());
    assertEquals(403, assertThrows(ApiException.class, () -> service.saveTarget(mapper.createObjectNode(), reader)).status());
    verifyNoInteractions(db, jdbc);
  }

  @Test void savingManualTargetIsRejectedBeforeAnyCompanyWriteWhenPersonnelEvenZeroIsConfigured() {
    personnelTargetRows.add(personnelTarget("2026-11", "甲", "0"));
    var body = mapper.createObjectNode().put("month", "2026-11").put("amount", "100").put("revision", 0);
    var failure = assertThrows(ApiException.class, () -> service.saveTarget(body, manager));
    assertEquals(409, failure.status());
    assertTrue(failure.getMessage().contains("销售业绩目标"));
    verify(db).rows(contains("FROM sales_targets WHERE month_key=? FOR UPDATE"), eq("2026-11"));
    verify(jdbc, never()).update(contains("dashboard_targets"), any(Object[].class));
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
  }

  @Test void manualSaveLocksPersonnelMonthBeforeCompanyRowAndUsesExplicitRepeatableRead() throws Exception {
    var annotation = DashboardService.class.getMethod("saveTarget", JsonNode.class, Actor.class)
        .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
    assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ, annotation.isolation());
    when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
      if (((String)call.getArgument(0)).startsWith("UPDATE dashboard_targets")) {
        targetRows.clear(); targetRows.add(target("2026-11", "500", 1));
      }
      return 1;
    });
    var body = mapper.createObjectNode().put("month", "2026-11").put("amount", "500").put("revision", 0);
    var saved = mapper.valueToTree(service.saveTarget(body, manager));
    assertEquals("manual", saved.path("source").asText());
    assertEquals("500.00", saved.path("manualAmount").asText());
    var sequence = inOrder(db, jdbc);
    sequence.verify(db).rows(contains("FROM sales_targets WHERE month_key=? FOR UPDATE"), eq("2026-11"));
    sequence.verify(jdbc).update(startsWith("INSERT IGNORE INTO dashboard_targets"), eq("2026-11"));
    sequence.verify(db).rows(contains("FROM dashboard_targets WHERE month_key=? FOR UPDATE"), eq("2026-11"));
    verify(db, times(1)).rows(contains("FROM sales_targets"), any(Object[].class));
    verify(jdbc, never()).update(contains("sales_targets"), any(Object[].class));
  }

  @Test void invalidTargetAmountsAndRevisionsAreRejectedBeforeAnyWrite() {
    for (String amount : List.of("-1", "1.001", "1000000000000", "NaN", "", "1e3")) {
      var body = mapper.createObjectNode().put("month", "2026-11").put("amount", amount).put("revision", 0);
      assertEquals(400, assertThrows(ApiException.class, () -> service.saveTarget(body, manager)).status(), amount);
    }
    var body = mapper.createObjectNode().put("month", "2026-11").put("amount", "100");
    assertThrows(ApiException.class, () -> service.saveTarget(body, manager));
    body.put("revision", -1);
    assertThrows(ApiException.class, () -> service.saveTarget(body, manager));
    body.put("revision", 0.5);
    assertThrows(ApiException.class, () -> service.saveTarget(body, manager));
    verifyNoInteractions(jdbc);
  }

  @Test void staleTargetRevisionIsAConflictAndDoesNotOverwriteTheLatestValue() {
    targetRows.add(target("2026-11", "100", 3));
    var body = mapper.createObjectNode().put("month", "2026-11").put("amount", "200").put("revision", 2);
    assertEquals(409, assertThrows(ApiException.class, () -> service.saveTarget(body, manager)).status());
    verify(jdbc, never()).update(startsWith("UPDATE dashboard_targets"), any(Object[].class));
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
  }

  @Test void savingAnExplicitZeroTargetPersistsItsRevisionAndAuditsTheChange() {
    when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
      if (((String)call.getArgument(0)).startsWith("UPDATE dashboard_targets")) {
        targetRows.clear();
        targetRows.add(target(call.getArgument(4), call.getArgument(1).toString(), 1));
      }
      return 1;
    });
    var body = mapper.createObjectNode().put("month", "2026-11").put("amount", "0").put("revision", 0);
    var saved = mapper.valueToTree(service.saveTarget(body, manager));
    money("0", saved.path("amount"));
    assertEquals(1, saved.path("revision").asInt());
    var detail = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(db).audit(eq("manager"), contains("2026-11"), detail.capture());
    assertEquals("DASHBOARD_TARGET_CHANGED", detail.getValue().path("type").asText());
    assertTrue(detail.getValue().path("before").path("amount").isNull());
    money("0", detail.getValue().path("after").path("amount"));
  }

  @Test void failedCompareAndSetDoesNotAuditASuccessfulTargetWrite() {
    targetRows.add(target("2026-11", "100", 3));
    when(jdbc.update(startsWith("UPDATE dashboard_targets"), any(Object[].class))).thenReturn(0);
    var body = mapper.createObjectNode().put("month", "2026-11").put("amount", "200").put("revision", 3);
    assertEquals(409, assertThrows(ApiException.class, () -> service.saveTarget(body, manager)).status());
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
  }

  private void selectedScope(List<String> ids) {
    scopeRows.clear();
    scopeRows.add(Map.of("mode", "selected", "department_ids", mapper.valueToTree(ids).toString(), "revision", 2L));
  }

  @Test void sharedDepartmentScopeChangesOnlyTargetDenominatorNotActualSales() {
    order("selected", "2026-11-02", "21", "6");
    order("hidden", "2026-11-02", "30", "10").put("owner", "小李");
    personnelTargetRows.add(personnelTarget("2026-11", "小王", "100"));
    personnelTargetRows.add(personnelTarget("2026-11", "小李", "200"));
    scopeAccounts.add(Map.of("owner", "小王", "departmentId", "d1", "department", "一部"));
    scopeAccounts.add(Map.of("owner", "小李", "departmentId", "d2", "department", "二部"));
    scopeDepartments.add(Map.of("id", "d1", "name", "一部")); scopeDepartments.add(Map.of("id", "d2", "name", "二部"));
    selectedScope(List.of("d1"));
    var report = sales(); var goal = report.path("target");
    assertEquals("100.00", goal.path("amount").asText()); assertEquals(1, goal.path("personCount").asInt());
    assertEquals("selected", goal.path("scope").path("mode").asText());
    assertEquals("51.00", report.path("summary").path("orderAmount").asText());
    assertEquals(2, report.path("orders").size());
    assertEquals(goal, mapper.valueToTree(service.targets(2026, manager)).path("targets").get(10));
    scopeRows.clear(); assertEquals("300.00", sales().path("target").path("amount").asText());
    verifyNoInteractions(jdbc);
  }

  @Test void selectedEmptyOrUnsetScopeTargetsStayZeroWithoutReactivatingManualFallback() {
    targetRows.add(target("2026-11", "777", 3));
    personnelTargetRows.add(personnelTarget("2026-11", "未匹配历史人员", "100"));
    selectedScope(List.of());
    var empty = sales().path("target");
    assertEquals("0.00", empty.path("amount").asText()); assertEquals("personnel", empty.path("source").asText());
    assertEquals(0, empty.path("personCount").asInt()); assertEquals("777.00", empty.path("manualAmount").asText());
    selectedScope(List.of("d1")); scopeDepartments.add(Map.of("id", "d1", "name", "一部"));
    scopeAccounts.add(Map.of("owner", "还未设置目标", "departmentId", "d1", "department", "一部"));
    var unset = sales().path("target");
    assertEquals("0.00", unset.path("amount").asText()); assertEquals("personnel", unset.path("source").asText());
    assertEquals(0, unset.path("personCount").asInt()); assertEquals(3, unset.path("revision").asInt());
    assertEquals(unset, mapper.valueToTree(service.targets(2026, manager)).path("targets").get(10));
  }

  @Test void customScopeRejectsAllIndependentCompanyTargetWritesBeforeCompanyMutation() {
    selectedScope(List.of());
    var body = mapper.createObjectNode().put("month", "2026-11").put("amount", "500").put("revision", 0);
    var failure = assertThrows(ApiException.class, () -> service.saveTarget(body, manager));
    assertEquals(409, failure.status()); assertTrue(failure.getMessage().contains("部门范围"));
    verify(db).rows(contains("FROM sales_target_scope WHERE id=1 FOR SHARE"));
    verify(jdbc, never()).update(contains("dashboard_targets"), any(Object[].class));
    verify(db, never()).audit(anyString(), anyString(), any(JsonNode.class));
  }
}
