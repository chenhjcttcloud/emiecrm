package com.emie.crm.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

/** Compares business fields only. ERP payments are never treated as CRM cash receipts. */
public final class JushuitanOrderDifference {
  static final List<String> TEXT = List.of("receiver_name", "receiver_mobile", "receiver_phone", "receiver_state",
      "receiver_city", "receiver_district", "receiver_address", "remark");
  private static final BigDecimal MAX = new BigDecimal("999999999999.99");
  private JushuitanOrderDifference() {}

  private static BigDecimal number(JsonNode value) {
    if (!value.isNumber() && !value.isTextual()) throw new IllegalArgumentException();
    return new BigDecimal(value.asText()).stripTrailingZeros();
  }
  private static BigDecimal money(JsonNode value) {
    var n = number(value);
    if (n.signum() < 0 || n.scale() > 2 || n.compareTo(MAX) > 0) throw new IllegalArgumentException();
    return n;
  }
  private static void decimal(ObjectNode object, String key, BigDecimal value) {
    object.put(key, value.stripTrailingZeros().toPlainString());
  }

  static ObjectNode canonical(ObjectMapper mapper, JsonNode order, JsonNode fallback, boolean remote) {
    var result = mapper.createObjectNode();
    decimal(result, "pay_amount", money(order.path("pay_amount")));
    for (String key : List.of("freight", "free_amount"))
      decimal(result, key, order.hasNonNull(key) ? money(order.path(key)) : BigDecimal.ZERO);
    var grouped = new TreeMap<String, ObjectNode>();
    if (!order.path("items").isArray() || order.path("items").isEmpty()) throw new IllegalArgumentException();
    for (var item : order.path("items")) {
      String sku = item.path("sku_id").asText().trim();
      if (sku.isBlank() || sku.length() > 40) throw new IllegalArgumentException();
      var qty = number(item.path("qty")); var price = money(item.path("price"));
      var amount = money(item.path("amount"));
      if (qty.signum() <= 0 || qty.scale() > 0 || qty.compareTo(new BigDecimal("1000000000")) > 0
          || amount.compareTo(qty.multiply(price)) != 0) throw new IllegalArgumentException();
      String id = sku + "\u0000" + price.toPlainString();
      var line = grouped.get(id);
      if (line == null) {
        line = mapper.createObjectNode().put("sku_id", sku); decimal(line,"price",price);
        decimal(line,"qty",qty);decimal(line,"amount",amount);grouped.put(id,line);
      } else {
        var combinedQty=number(line.path("qty")).add(qty);
        if(combinedQty.compareTo(new BigDecimal("1000000000"))>0)throw new IllegalArgumentException();
        decimal(line,"qty",combinedQty);
        decimal(line,"amount",money(line.path("amount")).add(amount));
      }
    }
    var items = result.putArray("items");grouped.values().forEach(items::add);
    var sum = grouped.values().stream().map(x -> number(x.path("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
    if (sum.subtract(number(result.path("free_amount"))).add(number(result.path("freight")))
        .compareTo(number(result.path("pay_amount"))) != 0) throw new IllegalArgumentException();
    // Missing or masked recipient values are unknown, never interpreted as deletion.
    for (String key : TEXT) {
      var value = order.path(key);
      result.put(key, remote && (!order.hasNonNull(key) || (key.startsWith("receiver_") && value.asText().contains("*")))
          ? fallback.path(key).asText("") : value.asText(""));
    }
    result.put("wms_co_id", order.path("wms_co_id").asLong(fallback.path("wms_co_id").asLong()));
    return result;
  }

  public static ObjectNode detect(ObjectMapper mapper, JsonNode record, JsonNode orders, JsonNode products, String env) {
    var result = mapper.createObjectNode();var blocked = result.putArray("blockedReasons");
    result.put("coreChanged", false);result.put("difference", BigDecimal.ZERO);
    if (orders.size() != 1 || orders.get(0).path("o_id").asLong() != record.path("oId").asLong()
        || !orders.get(0).path("so_id").asText().equals(record.path("soId").asText())
        || (orders.get(0).hasNonNull("shop_id") && orders.get(0).path("shop_id").asLong()!=record.path("request").path("shop_id").asLong())) {
      result.put("kind","IDENTITY");blocked.add("订单已拆分、合并或单号不匹配，仅回查状态，不能自动采纳内容");return result;
    }
    var actual = orders.get(0);
    ObjectNode before, after;
    try {
      before=canonical(mapper,record.path("request"),record.path("request"),false);
      after=canonical(mapper,actual,before,true);
    } catch (IllegalArgumentException e) {
      result.put("kind","INVALID");blocked.add("聚水潭商品数量、单价、明细金额或应付合计缺失/不一致，需人工核对");return result;
    }
    if (before.equals(after)) return null;
    result.put("kind","CONTENT");result.set("before",before);result.set("after",after);
    boolean core = !before.path("items").equals(after.path("items")) || !before.path("pay_amount").equals(after.path("pay_amount"))
        || !before.path("freight").equals(after.path("freight")) || !before.path("free_amount").equals(after.path("free_amount"));
    result.put("coreChanged",core);
    decimal(result,"difference",number(after.path("pay_amount")).subtract(number(before.path("pay_amount"))));
    var beforeOrder = record.path("effectiveOrder").isObject() ? record.path("effectiveOrder") : record.path("snapshot");
    result.set("beforeOrder",beforeOrder.deepCopy());
    if(core) {
      try {
        if(number(beforeOrder.path("total")).compareTo(number(before.path("pay_amount")))!=0
            || number(beforeOrder.path("paid")).compareTo(number(beforeOrder.path("total")))!=0)
          blocked.add("CRM执行金额或收款快照不一致，需先核对原单资金");
      } catch(IllegalArgumentException e) {blocked.add("CRM执行金额或收款快照缺失");}
    }
    if(number(after.path("pay_amount")).signum()<=0)blocked.add("零金额订单需人工核对，不能直接采纳执行");
    if(after.path("wms_co_id").asLong()<=0 && !before.path("wms_co_id").equals(after.path("wms_co_id")))
      blocked.add("聚水潭未返回有效仓库编号，需人工核对");
    ObjectNode effective = beforeOrder.deepCopy();
    var lines=core ? effective.putArray("items") : mapper.createArrayNode();
    for (var item : core ? after.path("items") : mapper.createArrayNode()) {
      JsonNode product = null;
      for (var candidate : products) if (candidate.path("source").asText().equals("jushuitan")
          && candidate.path("jstEnvironment").asText().equals(env)
          && candidate.path("jstSku").equals(item.path("sku_id"))) {
        if (product!=null) {product=null;break;} product=candidate;
      }
      if (product==null) {blocked.add("SKU "+item.path("sku_id").asText()+" 未唯一对应CRM商品，请先同步商品");continue;}
      lines.addObject().put("product",product.path("id").asText()).put("qty",number(item.path("qty")))
          .put("price",number(item.path("price")));
    }
    effective.put("total",number(after.path("pay_amount")));
    // paid is changed only in the transaction that records an actual cash difference.
    result.set("effectiveOrder",effective);
    if (number(after.path("freight")).signum()!=0 || number(after.path("free_amount")).signum()!=0)
      blocked.add("含运费或订单级优惠，当前CRM明细模型不能直接采纳，请人工核对");
    for (var item:actual.path("items")) {
      String state=item.path("item_status").asText("");
      if (Set.of("Cancelled","Replaced","Split","Delete","Merged","SentCancelled").contains(state))
        blocked.add("存在取消、替换或拆合明细，需人工核对");
      if (item.path("src_combine_sku_id").asText("").length()>0 || item.path("sku_type").asText("").equals("combine"))
        blocked.add("包含组合装明细，需人工核对原订单");
    }
    if (Set.of("Cancelled","Merged","Split","Delete").contains(actual.path("status").asText()))
      blocked.add("订单当前已取消或拆合，不能采纳为新的执行内容");
    result.put("upstreamStatus",actual.path("status").asText());
    return result;
  }
}
