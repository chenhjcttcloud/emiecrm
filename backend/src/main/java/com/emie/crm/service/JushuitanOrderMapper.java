package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Converts an authoritative CRM order, with explicit SKU mapping and recipient data. */
public class JushuitanOrderMapper {
  public static ObjectNode build(ObjectMapper mapper, JsonNode order, JsonNode products,
      JsonNode input, String soId, boolean sandbox) {
    require(!order.path("customerId").asText().isBlank(), "请先在订单中确认客户关联并保存");
    require(order.path("status").asText().equals("已批准"), "订单须先批准后提交发货");
    require(order.path("type").asText().equals("销售订单"), "目前仅支持销售订单发货");
    BigDecimal total, paid;
    try { total = new BigDecimal(order.path("total").asText()); paid = new BigDecimal(order.path("paid").asText("0")); }
    catch (Exception e) { throw new com.emie.crm.common.exception.ApiException("订单金额无效"); }
    require(total.signum() > 0 && total.compareTo(paid) == 0, "请完成收款后再提交发货；当前暂不支持账期发货");
    require(input.path("shopId").canConvertToLong() && input.path("shopId").asLong() > 0, "请选择聚水潭店铺");
    ObjectNode out = mapper.createObjectNode();
    out.put("shop_id", input.path("shopId").asLong()).put("so_id", soId)
        .put("shop_buyer_id", soId).put("shop_status", "WAIT_SELLER_SEND_GOODS")
        .put("order_date", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .put("pay_amount", total).put("freight", 0)
        .put("remark", (sandbox ? "【CRM沙箱测试，禁止实际发货】" : "") + "CRM订单：" + order.path("id").asText());
    for (var field : Map.of("receiver_name", "name", "receiver_mobile", "phone", "receiver_state", "province",
        "receiver_city", "city", "receiver_district", "district", "receiver_address", "address").entrySet()) {
      String text = input.path("recipient").path(field.getValue()).asText().trim();
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
      String sku = mapping.path("skuId").asText().trim();
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
    // This records CRM's already collected amount; it never initiates a payment.
    out.putObject("pay").put("outer_pay_id", soId + "-PAY").put("pay_date", out.path("order_date").asText())
        .put("payment", "CRM已收款").put("seller_account", "EMIE-CRM").put("buyer_account", soId).put("amount", paid);
    return out;
  }
}
