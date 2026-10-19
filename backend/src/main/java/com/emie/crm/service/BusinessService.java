package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessService {
  private final CrmRepository db;

  public BusinessService(CrmRepository db) {
    this.db = db;
  }

  public JsonNode state() {
    return db.state(false);
  }

  private JsonNode find(JsonNode values, String key, String value) {
    if (values.isArray()) for (var n : values) if (n.path(key).asText().equals(value)) return n;
    return NullNode.instance;
  }

  private BigDecimal number(JsonNode value, boolean optional) {
    if (value.isMissingNode() || value.isNull()) {
      if (optional) return BigDecimal.ZERO;
      throw new ApiException("订单金额无效");
    }
    try {
      return new BigDecimal(value.asText());
    } catch (Exception e) {
      throw new ApiException("金额必须为有效数字");
    }
  }

  private boolean same(JsonNode a, JsonNode b) {
    return a.equals(b)
        || (a.isNumber() && b.isNumber() && a.decimalValue().compareTo(b.decimalValue()) == 0);
  }

  private void validState(JsonNode n) {
    require(
        n.isObject()
            && n.path("orders").isArray()
            && n.path("customers").isArray()
            && n.path("products").isArray(),
        "数据结构无效");
    for (String key : List.of("orders", "customers", "products")) {
      var ids = new HashSet<String>();
      for (var entry : n.path(key))
        require(
            entry.isObject()
                && !entry.path("id").asText().isBlank()
                && ids.add(entry.path("id").asText()),
            "记录ID无效或重复");
    }
  }

  @Transactional
  public JsonNode save(JsonNode body, Actor actor) {
    actor.require("business.read");
    actor.require("business.write");
    var old = db.state(true);
    var input = body.path("data");
    validState(input);
    ObjectNode next = input.deepCopy();
    if (!old.isNull() && !same(body.path("revision"), old.path("revision")))
      throw new ApiException(409, "数据已由其他操作更新，请刷新后重试");
    if (!old.isNull())
      for (String key : List.of("orders", "customers"))
        for (var entry : old.path(key))
          require(
              !find(next.path(key), "id", entry.path("id").asText()).isNull(),
              "不能移除历史" + (key.equals("orders") ? "订单" : "客户"));
    List<JsonNode> receipts = new ArrayList<>();
    for (var entry : next.path("orders")) {
      var prior = find(old.path("orders"), "id", entry.path("id").asText());
      if (entry.path("status").asText().equals("已批准")
          && !prior.path("status").asText().equals("已批准")) actor.require("order.approve");
      BigDecimal total = number(entry.path("total"), false),
          paid = number(entry.path("paid"), true),
          before = number(prior.path("paid"), true),
          delta = paid.subtract(before);
      require(
          total.signum() >= 0
              && paid.signum() >= 0
              && delta.signum() >= 0
              && paid.compareTo(total) <= 0,
          "订单金额或收款金额无效");
      if (before.signum() > 0)
        for (String key : List.of("total", "customer", "type", "items"))
          require(same(entry.path(key), prior.path(key)), "已有收款的订单不可更改金额、产品、客户或类型");
      if (delta.signum() > 0) {
        actor.require("payment.write");
        require(entry.path("status").asText().equals("已批准"), "订单需先批准");
        String method = entry.path("method").asText("银行转账");
        require(method.equals("预收款核销") || !db.rows("SELECT id FROM payment_methods WHERE name=? AND status='启用'", method).isEmpty(), "请选择已启用的收款方式");
        require(
            !(entry.path("type").asText().equals("预收款订单") && method.equals("预收款核销")),
            "预收款订单不能使用核销方式");
        var r = db.object();
        r.put("id", UUID.randomUUID().toString());
        r.put("order", entry.path("id").asText());
        r.put("customer", entry.path("customer").asText());
        r.put("amount", delta.setScale(2, RoundingMode.HALF_UP));
        r.put("method", method);
        r.put("type", entry.path("type").asText());
        r.put("time", CrmRepository.now());
        r.put("operator", actor.name());
        receipts.add(r);
      }
    }
    if (body.has("receiptVerification")) {
      var verification = body.path("receiptVerification");
      var received = number(verification.path("amount"), false);
      require(received.signum() > 0 && received.stripTrailingZeros().scale() <= 2, "实际收款金额须大于零且最多两位小数");
      var allocated = receipts.stream().map(r -> r.path("amount").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
      require(!receipts.isEmpty() && received.compareTo(allocated) == 0, "实际收款金额与本次付款合计不一致");
      require(receipts.stream().allMatch(r -> r.path("customer").asText().equals(verification.path("customer").asText()) && r.path("method").asText().equals(verification.path("method").asText())), "收款客户或收款方式不一致");
    }
    for (var customer : next.path("customers")) {
      var prior = find(old.path("customers"), "id", customer.path("id").asText());
      if (!prior.isNull() && !same(prior.path("name"), customer.path("name"))) {
        for (var order : next.path("orders"))
          if (same(order.path("customer"), prior.path("name")))
            ((ObjectNode) order).put("customer", customer.path("name").asText());
        for (var r : receipts)
          if (same(r.path("customer"), prior.path("name")))
            ((ObjectNode) r).put("customer", customer.path("name").asText());
      }
    }
    List<JsonNode> all = new ArrayList<>(db.documents("receipts", false, 0));
    all.addAll(receipts);
    for (var r : all)
      for (var c : next.path("customers")) {
        var prior = find(old.path("customers"), "id", c.path("id").asText());
        if (!prior.isNull()
            && same(prior.path("name"), r.path("customer"))
            && !same(prior.path("name"), c.path("name"))) {
          ((ObjectNode) r).put("customer", c.path("name").asText());
          break;
        }
      }
    for (var c : next.path("customers")) {
      BigDecimal balance = BigDecimal.ZERO;
      for (var r : all)
        if (same(r.path("customer"), c.path("name"))) {
          BigDecimal amount = number(r.path("amount"), false);
          if (r.path("type").asText().equals("预收款订单")) balance = balance.add(amount);
          if (r.path("method").asText().equals("预收款核销")) balance = balance.subtract(amount);
        }
      require(balance.signum() >= 0, "客户预收款余额不足");
      ((ObjectNode) c).put("balance", balance.setScale(2, RoundingMode.HALF_UP));
    }
    next.put("revision", old.path("revision").asLong(0) + 1);
    db.state(next);
    all.forEach(db::receipt);
    db.audit(actor.name(), receipts.isEmpty() ? "保存业务数据" : "登记收款");
    return next;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> backup() {
    return Map.of(
        "version",
        1,
        "state",
        db.state(false),
        "receipts",
        db.documents("receipts", false, 0),
        "exported",
        CrmRepository.now());
  }

  @Transactional
  public void restore(JsonNode body, Actor actor) {
    require(body.path("version").asInt() == 1 && body.path("receipts").isArray(), "备份格式无效");
    validState(body.path("state"));
    var old = db.state(true);
    ObjectNode next = body.path("state").deepCopy();
    next.put("revision", old.path("revision").asLong() + 1);
    db.state(next);
    db.jdbc().update("DELETE FROM receipts");
    for (var r : body.path("receipts")) {
      require(!r.path("id").asText().isBlank(), "流水ID无效");
      db.receipt(r);
    }
    db.audit(actor.name(), "恢复数据备份");
  }
}
