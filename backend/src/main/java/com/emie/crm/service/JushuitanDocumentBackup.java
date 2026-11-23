package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class JushuitanDocumentBackup {
  private JushuitanDocumentBackup() {}

  private static final String SALE_EXPORT = """
      SELECT environment,order_id AS orderId,shop_id AS shopId,o_id AS oId,so_id AS soId,sale_metadata AS metadata
        FROM jst_document_sales ORDER BY environment,order_id,shop_id,o_id
      """;

  static Map<String, Object> exportLinks(CrmRepository db) {
    var sales = db.rows(SALE_EXPORT).stream().map(row -> {
      var fields = new LinkedHashMap<>(row);
      Object metadata = fields.remove("metadata");
      if (metadata != null) fields.put("metadata", db.parse(metadata.toString()));
      return fields;
    }).toList();
    return Map.of(
        "sales", sales,
        "purchases", db.rows("""
            SELECT environment,order_id AS orderId,po_id AS poId
              FROM jst_document_purchases ORDER BY environment,order_id,po_id
            """));
  }

  private record SaleLink(String environment, String orderId, JushuitanDocumentService.Sale sale) {}

  static void restoreLinks(CrmRepository db, JsonNode links, Map<String, JsonNode> orders,
                           List<JsonNode> shipments, JsonNode currentState) {
    var currentSettlements = new HashSet<String>();
    for (var row : currentState.path("orders"))
      if (JushuitanDocumentService.isSettlement(row)) currentSettlements.add(row.path("id").asText());
    boolean hasSettlements = orders.values().stream().anyMatch(JushuitanDocumentService::isSettlement);
    if (links.isMissingNode()) {
      require(currentSettlements.isEmpty() && !hasSettlements, "备份缺少聚水潭补录总单关联，请使用最新备份");
      for (var row : db.rows("SELECT order_id FROM jst_document_sales UNION SELECT order_id FROM jst_document_purchases")) {
        String id = row.get("order_id").toString();
        if (linkable(orders.get(id))) continue;
        db.jdbc().update("DELETE FROM jst_document_sales WHERE order_id=?", id);
        db.jdbc().update("DELETE FROM jst_document_purchases WHERE order_id=?", id);
      }
      return;
    }

    require(links.path("sales").isArray() && links.path("purchases").isArray(), "聚水潭关联备份格式无效");
    var shipmentOwners = new HashSet<List<String>>();
    var shipmentOrders = new HashSet<String>();
    var singleSaleOwners = new HashSet<List<String>>();
    var saleNumbers = new HashSet<List<String>>();
    var saleShopNumbers = new HashSet<List<String>>();
    for (var shipment : shipments) {
      String environment = shipment.path("environment").asText();
      String orderId = shipment.path("orderId").asText();
      shipmentOrders.add(orderId);
      String shopId = JushuitanDocumentService.shopIdentifier(shipment.path("request").path("shop_id"));
      addShipmentSale(shipmentOwners, saleNumbers, saleShopNumbers, environment, orderId, shopId,
          shipment.path("oId"), shipment.path("soId"));
      var amendment = shipment.path("amendment");
      addShipmentSale(shipmentOwners, saleNumbers, saleShopNumbers, environment, orderId, shopId,
          amendment.path("oldOId"), amendment.path("oldSoId"));
      for (var old : shipment.path("amendments")) {
        addShipmentSale(shipmentOwners, saleNumbers, saleShopNumbers, environment, orderId, shopId,
            old.path("oldOId"), old.path("oldSoId"));
      }
    }

    List<Object[]> sales = new ArrayList<>();
    var settlementSales = new HashMap<String, List<SaleLink>>();
    for (var sale : links.path("sales")) {
      String environment = sale.path("environment").asText();
      String orderId = sale.path("orderId").asText();
      String shopId = JushuitanDocumentService.shopIdentifier(sale.path("shopId"));
      String number = JushuitanDocumentService.identifier(sale.path("oId"));
      String soId = sale.path("soId").asText();
      validateOwner(environment, orderId, orders);
      boolean settlement = JushuitanDocumentService.isSettlement(orders.get(orderId));
      require(!shopId.isEmpty() && !number.isEmpty() && !soId.isBlank() && soId.length() <= 191
              && !shipmentOwners.contains(List.of(environment, orderId))
              && (!settlement || !shipmentOrders.contains(orderId))
              && (settlement || singleSaleOwners.add(List.of(environment, orderId)))
              && saleNumbers.add(List.of(environment, number))
              && saleShopNumbers.add(List.of(environment, shopId, soId)),
          "备份中的聚水潭销售单关联无效或重复");
      var document = new JushuitanDocumentService.Sale(number, shopId, soId, "manual");
      if (sale.hasNonNull("metadata"))
        document = JushuitanDocumentService.withMetadata(document, sale.path("metadata"));
      if (settlement) {
        require("Sent".equals(document.status()), "备份缺少聚水潭补录子单发货快照");
        settlementSales.computeIfAbsent(orderId, ignored -> new ArrayList<>()).add(new SaleLink(environment, orderId, document));
      }
      sales.add(new Object[] {environment, orderId, shopId, number, soId,
          sale.hasNonNull("metadata") ? db.encode(JushuitanDocumentService.saleMetadata(document)) : null});
    }
    checkSettlements(db, orders, currentSettlements, settlementSales);

    List<Object[]> purchases = new ArrayList<>();
    var purchaseLinks = new HashSet<List<String>>();
    for (var purchase : links.path("purchases")) {
      String environment = purchase.path("environment").asText();
      String orderId = purchase.path("orderId").asText();
      String number = JushuitanDocumentService.identifier(purchase.path("poId"));
      validateOwner(environment, orderId, orders);
      require(!number.isEmpty() && purchaseLinks.add(List.of(environment, orderId, number)),
          "备份中的聚水潭采购单关联无效或重复");
      purchases.add(new Object[] {environment, orderId, number});
    }

    db.jdbc().update("DELETE FROM jst_document_sales");
    db.jdbc().update("DELETE FROM jst_document_purchases");
    if (!sales.isEmpty()) db.jdbc().batchUpdate(
        "INSERT INTO jst_document_sales(environment,order_id,shop_id,o_id,so_id,sale_metadata) VALUES(?,?,?,?,?,?)", sales);
    if (!purchases.isEmpty()) db.jdbc().batchUpdate(
        "INSERT INTO jst_document_purchases(environment,order_id,po_id) VALUES(?,?,?)", purchases);
  }

  private static void checkSettlements(CrmRepository db, Map<String, JsonNode> orders,
                                       Set<String> currentSettlements, Map<String, List<SaleLink>> restored) {
    for (var entry : orders.entrySet()) {
      var order = entry.getValue();
      if (!JushuitanDocumentService.isSettlement(order)) continue;
      var links = restored.getOrDefault(entry.getKey(), List.of());
      require(!links.isEmpty() && links.size() <= JushuitanDocumentService.MAX_SETTLEMENT_SALES
          && links.stream().map(SaleLink::environment).distinct().count() == 1,
          "备份中的聚水潭补录总单关联无效");
      JushuitanDocumentService.requireSettlementPeriod(links.stream().map(SaleLink::sale).toList(), order.path("settlement"));
    }
    if (currentSettlements.isEmpty()) return;
    var current = new HashMap<String, Set<SaleLink>>();
    for (var row : db.rows(SALE_EXPORT)) {
      String orderId = row.get("orderId").toString();
      if (!currentSettlements.contains(orderId)) continue;
      var identity = new JushuitanDocumentService.Sale(row.get("oId").toString(),
          row.get("shopId").toString(), row.get("soId").toString(), "manual");
      Object metadata = row.get("metadata");
      require(metadata != null, "现有聚水潭补录总单关联缺少快照");
      var sale = JushuitanDocumentService.withMetadata(identity, db.parse(metadata.toString()));
      current.computeIfAbsent(orderId, ignored -> new HashSet<>())
          .add(new SaleLink(row.get("environment").toString(), orderId, sale));
    }
    for (var orderId : currentSettlements) {
      require(restored.containsKey(orderId) && current.getOrDefault(orderId, Set.of()).equals(new HashSet<>(restored.get(orderId))),
          "备份与现有聚水潭补录总单关联不一致，请使用最新备份");
    }
  }

  private static void addShipmentSale(Set<List<String>> owners, Set<List<String>> numbers,
                                      Set<List<String>> shopNumbers, String environment, String orderId,
                                      String shopId, JsonNode id, JsonNode soId) {
    String number = JushuitanDocumentService.identifier(id);
    if (!number.isEmpty()) {
      owners.add(List.of(environment, orderId));
      numbers.add(List.of(environment, number));
    }
    if (!shopId.isEmpty() && !soId.asText().isBlank())
      shopNumbers.add(List.of(environment, shopId, soId.asText()));
  }

  private static void validateOwner(String environment, String id, Map<String, JsonNode> orders) {
    require(Set.of("sandbox", "production").contains(environment) && id.length() <= 191 && linkable(orders.get(id)),
        "备份中的聚水潭关联订单或环境无效");
  }

  private static boolean linkable(JsonNode order) {
    return order != null && !"预收款订单".equals(order.path("type").asText());
  }
}
