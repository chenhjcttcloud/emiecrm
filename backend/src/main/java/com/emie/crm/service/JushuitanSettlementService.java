package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JushuitanSettlementService {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private final CrmRepository db;
  private final JushuitanDocumentService documents;
  private final TransactionTemplate transactions;

  public JushuitanSettlementService(CrmRepository db, JushuitanDocumentService documents,
                                   PlatformTransactionManager manager) {
    this.db = db;
    this.documents = documents;
    this.transactions = new TransactionTemplate(manager);
  }

  public record Preview(List<JushuitanDocumentService.Sale> sales, BigDecimal erpAmount) {}

  public List<JushuitanDocumentService.Sale> candidates(JsonNode body, Actor actor) {
    actor.require("business.read");
    actor.require("business.write");
    var period = period(body);
    var start = date(period.path("periodStart").asText());
    var end = date(period.path("periodEnd").asText());
    var searchStart = date(body.path("searchStart").asText(start.toString()));
    var searchEnd = date(body.path("searchEnd").asText(end.toString()));
    require(!searchStart.isBefore(start) && !searchEnd.isAfter(end) && !searchEnd.isBefore(searchStart)
        && searchEnd.isBefore(searchStart.plusDays(31)), "查询日期须在发货账期内，每次最多查询31天");
    return documents.settlementCandidates(body.path("shopId"), searchStart, searchEnd);
  }

  public Preview preview(JsonNode body, Actor actor) {
    actor.require("business.read");
    actor.require("business.write");
    return verify(body.path("sales"), period(body));
  }

  private Preview verify(JsonNode requested, ObjectNode period) {
    var sales = documents.verifySales(requested);
    JushuitanDocumentService.requireSettlementPeriod(sales, period);
    return new Preview(sales, JushuitanDocumentService.totalAmount(sales));
  }

  public ObjectNode create(JsonNode body, Actor actor) {
    actor.require("business.read");
    actor.require("business.write");
    require(body.path("customerConfirmed").isBoolean() && body.path("customerConfirmed").asBoolean(),
        "请确认所有销售单均属于所选付款客户");
    var period = period(body);
    var total = amount(body.path("total"));
    String customerId = body.path("customerId").asText();
    require(!customerId.isBlank(), "请选择付款客户");
    String note = body.path("note").asText().trim();
    String adjustment = body.path("adjustmentReason").asText().trim();
    require(note.length() <= 500 && adjustment.length() <= 500, "备注及差额说明最多500字");
    var verified = verify(body.path("sales"), period);
    var expected = body.path("expectedErpAmount");
    require(verified.erpAmount() == null ? expected.isNull()
        : expected.isNumber() && expected.decimalValue().compareTo(verified.erpAmount()) == 0,
        "聚水潭销售单金额已变化，请重新核验");
    require(verified.erpAmount() != null && total.compareTo(verified.erpAmount()) == 0
        || !adjustment.isBlank(), "请填写结算金额与聚水潭合计的差额说明");
    period.put("adjustmentReason", adjustment);
    period.put("erpAmount", verified.erpAmount());

    return transactions.execute(transaction -> {
      var state = db.state(true);
      require(state.isObject() && state.path("orders").isArray(), "请先初始化 CRM 业务数据");
      JsonNode customer = null;
      for (var item : state.path("customers")) {
        if (item.path("id").asText().equals(customerId)) {
          customer = item;
          break;
        }
      }
      require(customer != null, "付款客户不存在，请刷新重试");
      var next = (ObjectNode) state.deepCopy();
      String now = CrmRepository.now();
      String id = "JS-" + UUID.randomUUID();
      var profiles = db.rows("SELECT display_name FROM user_profiles WHERE name=?", actor.name());
      String owner = profiles.isEmpty() ? actor.name() : profiles.getFirst().get("display_name").toString();
      var order = db.object().put("id", id).put("type", "销售订单")
          .put("source", JushuitanDocumentService.SETTLEMENT_SOURCE)
          .put("customerId", customerId).put("customer", customer.path("name").asText())
          .put("owner", owner).put("note", note).put("paid", 0).put("total", total)
          .put("status", "审批中").put("created", now).put("updated", now);
      order.putArray("items");
      order.set("settlement", period);
      next.withArray("orders").insert(0, order);
      next.put("revision", state.path("revision").asLong() + 1);
      documents.saveSettlementSales(id, verified.sales(), actor);
      db.state(next);
      var detail = db.object().put("type", "JST_SETTLEMENT_CREATED").put("source", "CRM").put("orderId", id);
      detail.set("after", order);
      db.audit(actor.name(), "新建聚水潭结算补录单", detail);
      return next;
    });
  }

  private ObjectNode period(JsonNode input) {
    String cycle = input.path("cycle").asText();
    require(List.of("day", "month").contains(cycle), "请选择日结或月结");
    LocalDate start = date(input.path("periodStart").asText());
    LocalDate end = date(input.path("periodEnd").asText());
    require(!start.isAfter(end) && !end.isAfter(LocalDate.now(SHANGHAI)), "结算期间须有效，且不能晚于今天");
    if (cycle.equals("month"))
      require(start.getDayOfMonth() == 1 && end.equals(YearMonth.from(start).atEndOfMonth()),
          "月结须选择完整自然月");
    return db.object().put("cycle", cycle).put("periodStart", start.toString()).put("periodEnd", end.toString());
  }

  private static BigDecimal amount(JsonNode value) {
    String raw = value.asText();
    require(raw.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"), "结算金额最多两位小数");
    BigDecimal amount = new BigDecimal(raw);
    require(amount.signum() > 0, "结算金额须大于零");
    return amount;
  }

  static LocalDate date(String value) {
    try {
      require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "请选择有效日期");
      LocalDate result = LocalDate.parse(value);
      require(result.getYear() >= 2000, "请选择有效日期");
      return result;
    } catch (DateTimeParseException error) {
      throw new ApiException("请选择有效日期");
    }
  }

  static String receivedOn(String value, JsonNode order) {
    LocalDate received = date(value);
    LocalDate start = date(order.path("settlement").path("periodStart").asText());
    require(!received.isBefore(start) && !received.isAfter(LocalDate.now(SHANGHAI)),
        "到账日期须在结算开始日与今天之间");
    return received.toString();
  }

}
