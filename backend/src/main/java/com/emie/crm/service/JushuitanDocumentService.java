package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JushuitanDocumentService {
  static final String SALES = "/open/orders/single/query";
  static final String OUTBOUND = "/open/orders/out/simple/query";
  static final String RETURNS = "/open/aftersale/received/query";
  static final String PURCHASE = "/open/purchase/query";
  static final String PURCHASE_IN = "/open/webapi/wmsapi/purchasein/purchaseinquery";
  static final String SALES_BY_ORDER = """
      SELECT o_id,shop_id,so_id,sale_metadata FROM jst_document_sales WHERE environment=? AND order_id=? ORDER BY shop_id,o_id
      """;
  static final String PURCHASES_BY_ORDER = """
      SELECT po_id FROM jst_document_purchases WHERE environment=? AND order_id=? ORDER BY po_id
      """;
  private static final int PAGE_SIZE = 50;
  private static final int MAX_PAGES = 20;
  private static final int PURCHASE_BATCH_SIZE = 30;
  static final int MAX_SETTLEMENT_SALES = 500;
  static final String SETTLEMENT_SOURCE = "jushuitan-settlement";

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Sale(String id, String shopId, String soId, String source,
                     String shippedOn, String warehouseId, String status, BigDecimal amount, List<Item> items) {
    public Sale(String id, String shopId, String soId, String source) {
      this(id, shopId, soId, source, null, null, null, null, null);
    }
  }
  private record SaleKey(String shopId, String id) {}
  public record Item(String sku, String name, BigDecimal quantity) {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Document(String id, String status, String date, String warehouse, String afterSaleId,
                         List<Item> items, String saleId) {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Section(List<Document> rows, String error) {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Purchase(String id, String supplier, String status, String date, String sourceOrderId, List<Item> items,
                         Section receipts, String error) {}
  public record Documents(Sale salesOrder, Section outbound, Section returns, List<Purchase> purchases,
                          List<Sale> salesOrders) {}

  private final CrmRepository db;
  private final JushuitanClient client;
  private final TransactionTemplate tx;
  private final String environment;

  public JushuitanDocumentService(CrmRepository db, JushuitanClient client, PlatformTransactionManager manager,
                                  @Value("${CRM_JST_ENV:sandbox}") String environment) {
    this.db = db;
    this.client = client;
    this.tx = new TransactionTemplate(manager);
    this.environment = environment;
  }

  public Documents get(String orderId, Actor actor) {
    actor.require("business.read");
    requireSalesOrder(orderId, false);
    var shipment = shipment(orderId);
    var pushedSale = shipmentSale(shipment);
    var sales = pushedSale == null ? manualSales(orderId) : List.of(pushedSale);
    var saleIdsByShop = new LinkedHashMap<String, List<String>>();
    for (var sale : sales) saleIdsByShop.computeIfAbsent(sale.shopId(), ignored -> new ArrayList<>())
        .addAll(relatedSaleIds(sale, shipment));
    var purchaseIds = db.rows(PURCHASES_BY_ORDER, environment, orderId).stream()
        .map(row -> row.get("po_id").toString()).toList();
    return new Documents(sales.isEmpty() ? null : sales.getFirst(), saleDocuments(saleIdsByShop, false),
        saleDocuments(saleIdsByShop, true), purchases(purchaseIds), sales);
  }

  public void linkSale(String orderId, JsonNode body, Actor actor) {
    actor.require("business.write");
    require(!isSettlement(requireSalesOrder(orderId, false)), "补录总单请使用销售子单列表调整关联");
    boolean remove = removing(body);
    Sale verified = null;
    if (!remove) {
      require(shipmentSale(shipment(orderId)) == null, "该订单已通过发货记录关联聚水潭销售单");
      String shopId = requiredId(body.path("shopId"), "店铺编号");
      String orderNumber = requiredId(body.path("oId"), "聚水潭销售单号");
      verified = verifySale(shopId, orderNumber);
    }
    Sale next = verified;
    tx.executeWithoutResult(transaction -> {
      require(!isSettlement(requireSalesOrder(orderId, true)), "补录总单请使用销售子单列表调整关联");
      var priorSales = manualSales(orderId);
      require(priorSales.size() <= 1, "此订单有多个销售单关联，请核对后调整");
      var previous = priorSales.isEmpty() ? null : priorSales.getFirst();
      if (Objects.equals(previous, next)) return;
      if (next != null) {
        var shipment = shipment(orderId);
        require(shipment == null || (!shipment.path("busy").asBoolean()
                && shipment.path("status").asText().equals("FAILED")
                && !shipment.path("upstreamAttempted").asBoolean()),
            "该订单已有聚水潭发货记录，请先核对");
        requireAvailableSale(orderId, next);
      }
      db.jdbc().update("DELETE FROM jst_document_sales WHERE environment=? AND order_id=?", environment, orderId);
      if (next != null) db.jdbc().update(
          "INSERT INTO jst_document_sales(environment,order_id,shop_id,o_id,so_id) VALUES(?,?,?,?,?)",
          environment, orderId, next.shopId(), next.id(), next.soId());
      var details = auditDetails(orderId, "sale");
      details.set("before", db.tree(previous));
      details.set("after", db.tree(next));
      db.audit(actor.name(), remove ? "解除聚水潭销售单关联" : "关联聚水潭销售单", details);
    });
  }

  List<Sale> settlementCandidates(JsonNode shop, LocalDate start, LocalDate end) {
    String shopId = requiredId(shop, "店铺编号");
    var candidates = new LinkedHashMap<String, Sale>();
    // The ERP accepts at most seven calendar days per date query.
    for (var from = start; !from.isAfter(end); from = from.plusDays(7)) {
      var through = from.plusDays(6).isBefore(end) ? from.plusDays(6) : end;
      for (boolean archive : List.of(false, true)) {
        var filters = Map.of("shop_id", Long.parseLong(shopId), "date_type", 3, "status", "Sent",
            "modified_begin", from + " 00:00:00", "modified_end", through + " 23:59:59", "archive", archive);
        for (var row : queryRows(SALES, "orders", filters)) {
          var sale = settlementSale(row, shopId);
          if (sale == null || !"Sent".equals(sale.status()) || sale.shippedOn() == null
              || sale.shippedOn().compareTo(from.toString()) < 0 || sale.shippedOn().compareTo(through.toString()) > 0)
            continue;
          candidates.putIfAbsent(sale.id(), sale);
        }
      }
      require(candidates.size() <= 1000, "候选销售单超过1000张，请缩小查询日期范围");
    }
    return candidates.values().stream().filter(sale -> availableSale("", sale))
        .sorted(Comparator.comparing(Sale::shippedOn).reversed().thenComparing(Sale::id)).toList();
  }

  public List<Sale> verifySales(JsonNode rows) {
    var keys = saleKeys(rows, false);
    var byShop = new LinkedHashMap<String, List<String>>();
    for (var key : keys) byShop.computeIfAbsent(key.shopId(), ignored -> new ArrayList<>()).add(key.id());
    var verified = new HashMap<SaleKey, Sale>();
    var onlineNumbers = new HashSet<List<String>>();
    for (var shop : byShop.entrySet()) {
      var ids = shop.getValue();
      for (int offset = 0; offset < ids.size(); offset += PAGE_SIZE) {
        var batch = ids.subList(offset, Math.min(ids.size(), offset + PAGE_SIZE));
        var request = new HashMap<String, Object>();
        request.put("shop_id", Long.parseLong(shop.getKey()));
        request.put("o_ids", batch.stream().map(Long::parseLong).toList());
        var matches = new HashMap<String, Set<Sale>>();
        collectSettlementSales(matches, queryRows(SALES, "orders", request), shop.getKey(), batch);
        var missing = batch.stream().filter(id -> !matches.containsKey(id)).toList();
        if (!missing.isEmpty()) {
          request.put("archive", true);
          request.put("o_ids", missing.stream().map(Long::parseLong).toList());
          collectSettlementSales(matches, queryRows(SALES, "orders", request), shop.getKey(), missing);
        }
        for (String id : batch) {
          var candidates = matches.getOrDefault(id, Set.of());
          require(candidates.size() == 1, "未查询到唯一匹配的聚水潭销售单 " + id + "，请核对店铺和内部单号");
          var sale = candidates.iterator().next();
          require("Sent".equals(sale.status()) && sale.shippedOn() != null,
              "聚水潭销售单 " + id + " 未确认发货或缺少有效发货日期");
          require(onlineNumbers.add(List.of(sale.shopId(), sale.soId())), "同一店铺的线上单号重复，不能重复结算");
          verified.put(new SaleKey(sale.shopId(), sale.id()), sale);
        }
      }
    }
    return keys.stream().map(verified::get).toList();
  }

  private static void collectSettlementSales(Map<String, Set<Sale>> matches, List<JsonNode> rows,
                                             String shopId, List<String> requestedIds) {
    for (var row : rows) {
      var sale = settlementSale(row, shopId);
      if (sale != null && requestedIds.contains(sale.id()))
        matches.computeIfAbsent(sale.id(), ignored -> new HashSet<>()).add(sale);
    }
  }

  private static Sale settlementSale(JsonNode row, String shopId) {
    String id = identifier(row.path("o_id"));
    String soId = text(row, "so_id", 191);
    if (id.isEmpty() || soId.isBlank() || !sameSale(row, shopId, id)) return null;
    return new Sale(id, shopId, soId, "manual", shipmentDate(row.path("send_date").asText()),
        optionalId(row.path("wms_co_id")), text(row, "status", 64), documentAmount(row.path("pay_amount")), items(row));
  }

  public static void requireSettlementPeriod(List<Sale> sales, JsonNode settlement) {
    var start = shipmentDate(settlement.path("periodStart").asText());
    var end = shipmentDate(settlement.path("periodEnd").asText());
    require(start != null && end != null && start.compareTo(end) <= 0, "结算期间无效");
    for (var sale : sales) require(sale.shippedOn() != null && sale.shippedOn().compareTo(start) >= 0
        && sale.shippedOn().compareTo(end) <= 0, "聚水潭销售单 " + sale.id() + " 的发货日期不在结算期间");
  }

  public void replaceSettlementSales(String orderId, JsonNode body, Actor actor) {
    actor.require("business.write");
    var order = requireSalesOrder(orderId, false);
    require(isSettlement(order), "只有聚水潭补录总单支持批量更新销售单");
    require(body.path("customerConfirmed").isBoolean() && body.path("customerConfirmed").asBoolean(),
        "请确认所选销售单属于该结算客户");
    var expected = new HashSet<>(saleKeys(body.path("previousSales"), true));
    var next = verifySales(body.path("sales"));
    tx.executeWithoutResult(transaction -> {
      var current = requireSalesOrder(orderId, true);
      require(isSettlement(current), "只有聚水潭补录总单支持批量更新销售单");
      if (!current.path("customerId").equals(order.path("customerId")))
        throw new ApiException(409, "订单客户已更新，请刷新后重试");
      requireSettlementPeriod(next, current.path("settlement"));
      var previous = manualSales(orderId);
      var keys = saleKeys(previous);
      if (!keys.equals(expected)) throw new ApiException(409, "销售单关联已更新，请刷新后重试");
      if (keys.equals(saleKeys(next))) return;
      var snapshots = new HashMap<SaleKey, Sale>();
      for (var sale : previous) snapshots.put(new SaleKey(sale.shopId(), sale.id()), sale);
      var updated = next.stream().map(sale -> snapshots.getOrDefault(new SaleKey(sale.shopId(), sale.id()), sale)).toList();
      boolean paid = Optional.ofNullable(documentAmount(current.path("paid"))).orElse(BigDecimal.ZERO).signum() > 0;
      if (paid) actor.require("payment.write");
      // Relinking does not amend the total or its recorded adjustment reason, even before approval.
      var before = totalAmount(previous);
      var after = totalAmount(next);
      var stored = totalAmount(updated);
      require(before != null && after != null && stored != null && before.compareTo(after) == 0 && before.compareTo(stored) == 0,
          "调整销售单关联须保持聚水潭合计一致；金额不同请核对后重新补录");
      replaceSales(orderId, previous, updated, actor);
    });
  }

  // The caller's business transaction already holds the state row lock.
  @Transactional(propagation = Propagation.MANDATORY)
  public void saveSettlementSales(String orderId, List<Sale> sales, Actor actor) {
    actor.require("business.write");
    require(!sales.isEmpty(), "请至少关联一张聚水潭销售单");
    require(manualSales(orderId).isEmpty(), "订单已有销售单关联");
    replaceSales(orderId, List.of(), sales, actor);
  }

  private void replaceSales(String orderId, List<Sale> previous, List<Sale> next, Actor actor) {
    require(shipment(orderId) == null, "补录总单已有聚水潭发货记录，请先核对");
    for (var sale : next) requireAvailableSale(orderId, sale);
    var retained = saleKeys(next);
    var existing = saleKeys(previous);
    for (var sale : previous) {
      if (!retained.contains(new SaleKey(sale.shopId(), sale.id())))
        db.jdbc().update("DELETE FROM jst_document_sales WHERE environment=? AND order_id=? AND o_id=?",
            environment, orderId, sale.id());
    }
    for (var sale : next) {
      if (!existing.contains(new SaleKey(sale.shopId(), sale.id())))
        db.jdbc().update("""
            INSERT INTO jst_document_sales(environment,order_id,shop_id,o_id,so_id,sale_metadata) VALUES(?,?,?,?,?,?)
            """, environment, orderId, sale.shopId(), sale.id(), sale.soId(), db.encode(saleMetadata(sale)));
    }
    var details = auditDetails(orderId, "sales");
    details.set("before", db.tree(previous));
    details.set("after", db.tree(next));
    db.audit(actor.name(), "更新聚水潭销售子单关联", details);
  }

  private static Set<SaleKey> saleKeys(List<Sale> sales) {
    var keys = new HashSet<SaleKey>();
    for (var sale : sales) keys.add(new SaleKey(sale.shopId(), sale.id()));
    return keys;
  }

  private static List<SaleKey> saleKeys(JsonNode rows, boolean allowEmpty) {
    require(rows.isArray() && rows.size() <= MAX_SETTLEMENT_SALES && (allowEmpty || !rows.isEmpty()),
        "请提供" + (allowEmpty ? "0" : "1") + "至" + MAX_SETTLEMENT_SALES + "张聚水潭销售单");
    var keys = new ArrayList<SaleKey>();
    var ids = new HashSet<String>();
    for (var row : rows) {
      String shopId = requiredId(row.path("shopId"), "店铺编号");
      String id = requiredId(row.path("oId"), "聚水潭销售单号");
      require(ids.add(id), "聚水潭销售单号 " + id + " 重复");
      keys.add(new SaleKey(shopId, id));
    }
    return keys;
  }

  public static BigDecimal totalAmount(List<Sale> sales) {
    BigDecimal total = BigDecimal.ZERO;
    for (var sale : sales) {
      if (sale.amount() == null) return null;
      total = total.add(sale.amount());
    }
    return total;
  }

  public void linkPurchase(String orderId, JsonNode body, Actor actor) {
    actor.require("business.write");
    requireSalesOrder(orderId, false);
    boolean remove = removing(body);
    String id = requiredId(body.path("poId"), "采购单号");
    if (!remove) {
      var found = queryRows(PURCHASE, "datas", Map.of("po_ids", List.of(id)));
      require(found.stream().anyMatch(row -> id.equals(identifier(row.path("po_id")))), "未查询到对应采购单");
    }
    tx.executeWithoutResult(transaction -> {
      requireSalesOrder(orderId, true);
      boolean linked = db.rows(PURCHASES_BY_ORDER, environment, orderId).stream()
          .anyMatch(row -> id.equals(row.get("po_id").toString()));
      if (linked == !remove) return;
      if (remove) db.jdbc().update(
          "DELETE FROM jst_document_purchases WHERE environment=? AND order_id=? AND po_id=?",
          environment, orderId, id);
      else db.jdbc().update("INSERT INTO jst_document_purchases(environment,order_id,po_id) VALUES(?,?,?)",
          environment, orderId, id);
      db.audit(actor.name(), remove ? "解除聚水潭采购单关联" : "关联聚水潭采购单",
          auditDetails(orderId, "purchase").put("documentId", id).put("removed", remove));
    });
  }

  private ObjectNode auditDetails(String orderId, String documentType) {
    return db.object().put("type", "JST_DOCUMENT_LINK").put("source", "CRM")
        .put("orderId", orderId).put("environment", environment).put("documentType", documentType);
  }

  static boolean isSettlement(JsonNode order) {
    return order != null && SETTLEMENT_SOURCE.equals(order.path("source").asText());
  }

  private JsonNode requireSalesOrder(String orderId, boolean lock) {
    require(orderId.length() <= 191, "订单编号无效");
    for (var order : db.state(lock).path("orders")) {
      if (orderId.equals(order.path("id").asText())) {
        require(!"预收款订单".equals(order.path("type").asText()), "仅销售订单可关联单据");
        return order;
      }
    }
    throw new ApiException(404, "订单不存在");
  }

  private JsonNode shipment(String orderId) {
    var rows = db.rows("SELECT payload FROM jst_shipments WHERE order_id=?", orderId);
    if (rows.isEmpty()) return null;
    var record = db.parse(rows.getFirst().get("payload").toString());
    return environment.equals(record.path("environment").asText()) ? record : null;
  }

  private Sale shipmentSale(JsonNode record) {
    if (record == null) return null;
    String id = identifier(record.path("oId"));
    String shopId = identifier(record.path("request").path("shop_id"));
    return id.isEmpty() || shopId.isEmpty() ? null : new Sale(id, shopId, text(record, "soId", 191), "shipment");
  }

  private List<Sale> manualSales(String orderId) {
    var sales = new ArrayList<Sale>();
    for (var row : db.rows(SALES_BY_ORDER, environment, orderId)) {
      var identity = new Sale(row.get("o_id").toString(), row.get("shop_id").toString(), row.get("so_id").toString(), "manual");
      Object metadata = row.get("sale_metadata");
      sales.add(metadata == null ? identity : withMetadata(identity, db.parse(metadata.toString())));
    }
    return List.copyOf(sales);
  }

  static Map<String, Object> saleMetadata(Sale sale) {
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("shippedOn", sale.shippedOn());
    metadata.put("warehouseId", sale.warehouseId());
    metadata.put("status", sale.status());
    metadata.put("amount", sale.amount());
    metadata.put("items", sale.items());
    return metadata;
  }

  static Sale withMetadata(Sale identity, JsonNode metadata) {
    require(metadata.isObject(), "聚水潭销售单快照格式无效");
    String shippedOn = shipmentDate(metadata.path("shippedOn").asText());
    String warehouseId = optionalId(metadata.path("warehouseId"));
    String status = text(metadata, "status", 64);
    BigDecimal amount = documentAmount(metadata.path("amount"));
    require((!metadata.hasNonNull("shippedOn") || shippedOn != null)
        && (!metadata.hasNonNull("warehouseId") || warehouseId != null)
        && (!metadata.hasNonNull("amount") || amount != null), "聚水潭销售单快照字段无效");
    var items = new ArrayList<Item>();
    var rows = metadata.path("items");
    require(rows.isMissingNode() || rows.isNull() || rows.isArray(), "聚水潭销售单商品快照格式无效");
    for (var row : rows) {
      String sku = text(row, "sku", 191);
      String quantity = decimalText(row.path("quantity"));
      require(!sku.isEmpty() && quantity.length() <= 40 && quantity.matches("-?[0-9]+(?:\\.[0-9]+)?"),
          "聚水潭销售单商品快照格式无效");
      items.add(new Item(sku, text(row, "name", 250), new BigDecimal(quantity)));
    }
    return new Sale(identity.id(), identity.shopId(), identity.soId(), identity.source(),
        shippedOn, warehouseId, status.isEmpty() ? null : status, amount, List.copyOf(items));
  }

  private static String optionalId(JsonNode node) {
    String id = identifier(node);
    return id.isEmpty() ? null : id;
  }

  private static String shipmentDate(String text) {
    if (text.length() < 10 || (text.length() > 10 && text.charAt(10) != ' ' && text.charAt(10) != 'T')) return null;
    try {
      return LocalDate.parse(text.substring(0, 10)).toString();
    } catch (DateTimeParseException error) {
      return null;
    }
  }

  private static BigDecimal documentAmount(JsonNode node) {
    String raw = decimalText(node);
    if (raw.length() > 40 || !raw.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
    var amount = new BigDecimal(raw);
    if (amount.stripTrailingZeros().scale() > 2 || amount.compareTo(new BigDecimal("999999999999.99")) > 0) return null;
    return amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  private static String decimalText(JsonNode node) {
    if (!node.isNumber()) return node.asText();
    var number = node.decimalValue();
    if (Math.abs((long) number.scale()) > 40 || number.precision() > 40) return "";
    return number.toPlainString();
  }

  private void requireAvailableSale(String orderId, Sale sale) {
    require(availableSale(orderId, sale), "该聚水潭销售单已关联其他 CRM 订单");
  }

  private boolean availableSale(String orderId, Sale sale) {
    var manual = db.rows("""
        SELECT order_id FROM jst_document_sales WHERE environment=? AND order_id<>?
          AND (o_id=? OR (shop_id=? AND so_id=?))
        """, environment, orderId, sale.id(), sale.shopId(), sale.soId());
    if (!manual.isEmpty()) return false;
    var sent = db.rows("""
        SELECT order_id FROM jst_shipments WHERE order_id<>?
          AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.environment'))=?
          AND (JSON_UNQUOTE(JSON_EXTRACT(payload,'$.oId'))=?
            OR (JSON_UNQUOTE(JSON_EXTRACT(payload,'$.request.shop_id'))=?
              AND (JSON_UNQUOTE(JSON_EXTRACT(payload,'$.soId'))=?
                OR JSON_SEARCH(payload,'one',?,NULL,'$.amendment.oldSoId','$.amendments[*].oldSoId') IS NOT NULL)))
        """, orderId, environment, sale.id(), sale.shopId(), sale.soId(), sale.soId());
    return sent.isEmpty();
  }

  private Sale verifySale(String shopId, String id) {
    var request = new HashMap<String, Object>(salesParameters(shopId, id));
    var matches = exactSaleRows(queryRows(SALES, "orders", request), shopId, id);
    if (matches.isEmpty()) {
      request.put("archive", true);
      matches = exactSaleRows(queryRows(SALES, "orders", request), shopId, id);
    }
    require(matches.size() == 1, "未查询到唯一匹配的聚水潭销售单，请核对店铺和内部单号");
    return new Sale(id, shopId, matches.iterator().next(), "manual");
  }

  private static Set<String> exactSaleRows(List<JsonNode> rows, String shopId, String id) {
    var matches = new HashSet<String>();
    for (var row : rows) {
      String soId = text(row, "so_id", 191);
      if (!soId.isBlank() && sameSale(row, shopId, id)) matches.add(soId);
    }
    return matches;
  }

  private static boolean sameSale(JsonNode row, String shopId, String id) {
    return id.equals(identifier(row.path("o_id"))) && shopId.equals(identifier(row.path("shop_id")));
  }

  private static Map<String, Object> salesParameters(String shopId, String id) {
    return Map.of("shop_id", Long.parseLong(shopId), "o_ids", List.of(Long.parseLong(id)));
  }

  private static List<String> relatedSaleIds(Sale sale, JsonNode shipment) {
    var ids = new LinkedHashSet<String>();
    ids.add(sale.id());
    if ("shipment".equals(sale.source()) && !sale.soId().isBlank()) {
      for (var row : shipment.path("lastExternalObservation").path("orders")) {
        String id = identifier(row.path("o_id"));
        if (!id.isEmpty() && sale.shopId().equals(identifier(row.path("shop_id")))
            && sale.soId().equals(row.path("so_id").asText())) ids.add(id);
      }
    }
    return List.copyOf(ids);
  }

  private Section saleDocuments(Map<String, List<String>> saleIdsByShop, boolean returned) {
    var rows = new LinkedHashMap<String, Document>();
    String error = null;
    for (var shop : saleIdsByShop.entrySet()) {
      var ids = shop.getValue();
      var shopId = shop.getKey();
      for (int start = 0; start < ids.size(); start += PAGE_SIZE) {
        var batch = ids.subList(start, Math.min(start + PAGE_SIZE, ids.size()));
        var request = new HashMap<String, Object>();
        if (returned) {
          request.put("shop_id", shopId);
          request.put("o_ids", batch);
        } else {
          request.put("shop_id", Long.parseLong(shopId));
          request.put("o_ids", batch.stream().map(Long::parseLong).toList());
        }
        for (boolean archive : returned ? List.of(false) : List.of(false, true)) {
          if (!returned) request.put("archive", archive);
          try {
            for (var row : queryRows(returned ? RETURNS : OUTBOUND, "datas", request)) {
              String saleId = identifier(row.path("o_id"));
              if (shopId.equals(identifier(row.path("shop_id"))) && batch.contains(saleId)) {
                addDocument(rows, row, returned ? identifier(row.path("as_id")) : null, saleId);
              }
            }
          } catch (ApiException e) {
            error = e.getMessage();
          }
        }
      }
    }
    return new Section(List.copyOf(rows.values()), error);
  }

  private List<Purchase> purchases(List<String> ids) {
    var result = new ArrayList<Purchase>();
    for (int start = 0; start < ids.size(); start += PURCHASE_BATCH_SIZE) {
      var batch = ids.subList(start, Math.min(start + PURCHASE_BATCH_SIZE, ids.size()));
      var orders = new HashMap<String, JsonNode>();
      String ordersError = null;
      try {
        for (var row : queryRows(PURCHASE, "datas", Map.of("po_ids", batch))) {
          String id = identifier(row.path("po_id"));
          if (batch.contains(id)) orders.putIfAbsent(id, row);
        }
      } catch (ApiException e) {
        ordersError = e.getMessage();
      }

      var receipts = new HashMap<String, Map<String, Document>>();
      String receiptsError = null;
      if (!orders.isEmpty()) {
        try {
          var numbers = orders.keySet().stream().map(Long::parseLong).toList();
          for (var row : queryRows(PURCHASE_IN, "datas", Map.of("po_ids", numbers))) {
            String id = identifier(row.path("po_id"));
            if (orders.containsKey(id)) {
              addDocument(receipts.computeIfAbsent(id, key -> new LinkedHashMap<>()), row, null, null);
            }
          }
        } catch (ApiException e) {
          receiptsError = e.getMessage();
        }
      }

      for (var id : batch) {
        var row = orders.get(id);
        if (row == null) {
          var error = ordersError == null ? "未查询到对应采购单" : ordersError;
          result.add(new Purchase(id, "", "", "", "", List.of(), new Section(List.of(), null), error));
          continue;
        }
        var section = new Section(List.copyOf(receipts.getOrDefault(id, Map.of()).values()), receiptsError);
        result.add(new Purchase(id, text(row, "seller", 191), text(row, "status", 64), text(row, "po_date", 64),
            identifier(row.path("source_o_id")), items(row), section, null));
      }
    }
    return result;
  }

  private List<JsonNode> queryRows(String path, String key, Map<String, ?> filters) {
    var rows = new ArrayList<JsonNode>();
    var request = new HashMap<String, Object>(filters);
    for (int page = 1; page <= MAX_PAGES; page++) {
      request.put("page_index", page);
      request.put("page_size", PAGE_SIZE);
      var response = client.query(path, Map.copyOf(request));
      if (response.path("code").asInt(-1) == 190)
        throw new ApiException(503, "当前聚水潭授权未开通此查询接口");
      if (response.path("code").asInt(-1) != 0)
        throw new ApiException(502, "聚水潭查询失败，请稍后重试");
      var data = response.path("data");
      var pageRows = data.path(key);
      if (!pageRows.isArray())
        throw new ApiException(502, "聚水潭返回的单据数据格式异常");
      pageRows.forEach(rows::add);
      if (!data.path("has_next").asBoolean()) return rows;
    }
    throw new ApiException(502, "聚水潭单据分页未完成，请稍后重试");
  }

  private static void addDocument(Map<String, Document> rows, JsonNode row, String afterSaleId, String saleId) {
    String id = identifier(row.path("io_id"));
    if (id.isEmpty()) return;
    String warehouse = text(row, "warehouse", 191);
    if (warehouse.isEmpty()) warehouse = identifier(row.path("wms_co_id"));
    rows.putIfAbsent(saleId == null ? id : saleId + ":" + id, new Document(id, text(row, "status", 64),
        text(row, "io_date", 64), warehouse, afterSaleId == null || afterSaleId.isEmpty() ? null : afterSaleId,
        items(row), saleId));
  }

  private static List<Item> items(JsonNode row) {
    var items = new ArrayList<Item>();
    for (var item : row.path("items")) {
      String sku = text(item, "sku_id", 191);
      String quantity = decimalText(item.path("qty"));
      if (!sku.isEmpty() && quantity.length() <= 40 && quantity.matches("-?[0-9]+(?:\\.[0-9]+)?")) {
        items.add(new Item(sku, text(item, "name", 250), new BigDecimal(quantity)));
      }
    }
    return items;
  }

  static String identifier(JsonNode value) {
    if (!value.isTextual() && !value.isIntegralNumber()) return "";
    String id = value.asText().strip();
    if (!id.matches("[1-9][0-9]{0,18}")) return "";
    return id.length() < 19 || id.compareTo(Long.toString(Long.MAX_VALUE)) <= 0 ? id : "";
  }

  private static String requiredId(JsonNode value, String label) {
    String id = identifier(value);
    require(!id.isEmpty(), label + "必须为有效的数字编号");
    return id;
  }

  private static boolean removing(JsonNode body) {
    require(body.isObject() && (!body.has("remove") || body.path("remove").isBoolean()), "请求格式无效");
    return body.path("remove").asBoolean(false);
  }

  private static String text(JsonNode row, String key, int limit) {
    String value = row.path(key).asText("").replaceAll("\\p{Cntrl}", " ").strip();
    return value.substring(0, Math.min(limit, value.length()));
  }
}
