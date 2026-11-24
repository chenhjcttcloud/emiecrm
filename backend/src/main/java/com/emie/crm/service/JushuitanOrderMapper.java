package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Converts an authoritative CRM order, with explicit SKU mapping and recipient data. */
public class JushuitanOrderMapper {
  static String buyerAccount(String customerId) {
    require(customerId != null && !customerId.isBlank(), "请先确认客户关联");
    try {
      // Keep one bounded, name-independent buyer identity across this customer's orders.
      return "CRM-C-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(customerId.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  public static ObjectNode build(ObjectMapper mapper, JsonNode order, JsonNode products,
      JsonNode input, String soId, boolean sandbox) {
    return build(mapper, order, products, input, soId, sandbox, false, false);
  }

  /** Server-selected credit settlement; callers must authorize the credit workflow separately. */
  public static ObjectNode buildCredit(ObjectMapper mapper, JsonNode order, JsonNode products,
      JsonNode input, String soId, boolean sandbox) {
    return build(mapper, order, products, input, soId, sandbox, true, false);
  }

  /** A separately authorized, durable submission task releases the ERP order for financial review. */
  public static ObjectNode buildSubmittedCredit(ObjectMapper mapper, JsonNode order, JsonNode products,
      JsonNode input, String soId, boolean sandbox) {
    return build(mapper, order, products, input, soId, sandbox, true, true);
  }

  private static ObjectNode build(ObjectMapper mapper, JsonNode order, JsonNode products,
      JsonNode input, String soId, boolean sandbox, boolean credit, boolean submitted) {
    require(!order.path("customerId").asText().isBlank(), "请先在订单中确认客户关联并保存");
    if (submitted) {
      require(credit && JushuitanCreditPushPolicy.optedIn(order)
          && Set.of("草稿", "审批中", "已批准").contains(order.path("status").asText()),
          "仅已正式提交的账期销售订单可推送聚水潭审核");
    } else require(order.path("status").asText().equals("已批准"), "订单须先批准后提交发货");
    require(order.path("type").asText().equals("销售订单"), "目前仅支持销售订单发货");
    BigDecimal total, paid;
    try { total = new BigDecimal(order.path("total").asText()); paid = new BigDecimal(order.path("paid").asText("0")); }
    catch (Exception e) { throw new com.emie.crm.common.exception.ApiException("订单金额无效"); }
    if (credit) {
      require(total.signum() > 0 && paid.signum() >= 0 && paid.compareTo(total) <= 0
          && total.stripTrailingZeros().scale() <= 2 && paid.stripTrailingZeros().scale() <= 2,
          "账期订单总额及真实已收金额无效");
    } else {
      require(total.signum() > 0 && total.compareTo(paid) == 0, "请完成收款后再提交发货；当前暂不支持账期发货");
    }
    require(input.path("shopId").isIntegralNumber() && input.path("shopId").canConvertToLong()
        && input.path("shopId").asLong() > 0, "请选择聚水潭店铺");
    require(input.path("warehouseId").isIntegralNumber() && input.path("warehouseId").canConvertToLong()
        && input.path("warehouseId").asLong() > 0, "请手工选择发货仓库");
    ObjectNode out = mapper.createObjectNode();
    out.put("wms_co_id", input.path("warehouseId").asLong());
    out.put("shop_id", input.path("shopId").asLong()).put("so_id", soId)
        .put("shop_buyer_id", buyerAccount(order.path("customerId").asText())).put("shop_status", "WAIT_SELLER_SEND_GOODS")
        .put("order_date", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .put("pay_amount", total).put("freight", 0)
        .put("remark", (sandbox ? "【CRM沙箱测试，禁止实际发货】" : "") + "CRM订单：" + order.path("id").asText()
            + (credit ? creditRemark(order, total, paid, submitted) : ""));
    var recipient = input.path("recipient");
    if (credit) require(CreditShippingAddress.valid(recipient), CreditShippingAddress.MESSAGE);
    boolean hasRegions = recipient.has("province") || recipient.has("city") || recipient.has("district");
    for (var field : Map.of("receiver_name", "name", "receiver_mobile", "phone", "receiver_state", "province",
        "receiver_city", "city", "receiver_district", "district", "receiver_address", "address").entrySet()) {
      boolean region = Set.of("province", "city", "district").contains(field.getValue());
      if (credit && region && !hasRegions) continue;
      require(!credit || recipient.path(field.getValue()).isTextual(), "请完整填写收件人、电话及省市区地址");
      String text = recipient.path(field.getValue()).asText().trim();
      require(!text.isBlank() && text.length() <= (field.getValue().equals("address") ? 200 : 50), "请完整填写收件人、电话及省市区地址");
      out.put(field.getKey(), text);
    }
    require(out.path("receiver_mobile").asText().matches("[0-9+() -]{7,30}"), "收件电话格式无效");
    var lines = order.path("items");
    var mappings = input.path("items");
    require(lines.isArray() && !lines.isEmpty() && mappings.isArray() && mappings.size() == lines.size(), "请逐项核对商品SKU");
    Map<String, JsonNode> byId = new HashMap<>();
    products.forEach(p -> byId.put(p.path("id").asText(), p));
    ArrayNode items = out.putArray("items");
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      var mapping = mappings.get(i);
      var product = byId.get(line.path("product").asText());
      require(product != null, "订单中的商品已不存在");
      require(product.path("source").asText().equals("jushuitan") && product.path("enabled").asBoolean()
          && product.path("jstEnvironment").asText().equals(sandbox ? "sandbox" : "production"), "请先从当前聚水潭环境同步已启用商品，历史商品需重新选择");
      String sku = product.path("jstSku").asText().trim();
      require(mapping.path("skuId").asText(sku).equals(sku), "商品SKU须以聚水潭同步结果为准，不能手工替换");
      require(!sku.isBlank() && sku.length() <= 40, "请填写每项商品的聚水潭 SKU 编码");
      BigDecimal qty;
      BigDecimal price;
      try { qty = new BigDecimal(line.path("qty").asText()); price = new BigDecimal(line.path("price").asText()); }
      catch (Exception e) { throw new com.emie.crm.common.exception.ApiException("商品数量或金额无效"); }
      require(qty.signum() > 0 && qty.stripTrailingZeros().scale() <= 0 && qty.compareTo(new BigDecimal("1000000000")) <= 0,
          "聚水潭发货数量须为正整数");
      require(price.signum() >= 0 && price.stripTrailingZeros().scale() <= 2, "商品单价须为非负金额，最多两位小数");
      var amount = qty.multiply(price).setScale(2, RoundingMode.UNNECESSARY);
      sum = sum.add(amount);
      items.addObject().put("sku_id", sku).put("shop_sku_id", sku).put("name", product.path("name").asText())
          .put("qty", qty.intValueExact()).put("amount", amount).put("price", price).put("base_price", price)
          .put("outer_oi_id", soId + "-" + (i + 1));
    }
    require(sum.compareTo(total) == 0, "订单总额与商品合计不一致");
    // Credit explicitly settles the ERP order for release; it is not a CRM cash receipt.
    // The legacy path continues to record only CRM's already collected amount.
    out.putObject("pay").put("outer_pay_id", soId + "-PAY").put("pay_date", out.path("order_date").asText())
        .put("payment", credit ? "账期挂账（非实收）" : "CRM已收款").put("seller_account", "EMIE-CRM")
        .put("buyer_account", soId).put("amount", credit ? total : paid);
    return out;
  }

  private static String creditRemark(JsonNode order, BigDecimal total, BigDecimal paid, boolean submitted) {
    String customer = order.path("customer").asText().trim();
    if (customer.isBlank()) customer = order.path("customerId").asText();
    if (customer.length() > 200) customer = customer.substring(0, 200);
    return "；客户：" + customer + "；审批快照时CRM已收：" + paid.setScale(2).toPlainString()
        + "；审批快照应收：" + total.subtract(paid).setScale(2).toPlainString()
        + "；账期挂账（非实收）结算金额：" + total.setScale(2).toPlainString()
        + "；ERP按全额结算放行，付款状态为已付，不代表现金到账"
        + (submitted ? "；正式提交即推单，财务须在聚水潭审核，飞书审批并行；发货以ERP审核为准" : "");
  }
}
