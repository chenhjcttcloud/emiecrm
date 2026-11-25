package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Preserves issued short numbers and the historical supplement backfill sequence without changing link IDs. */
final class SettlementOrderNumbers {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final Pattern FORMAT = Pattern.compile("([0-9]{4}-[0-9]{2})-([0-9]{8,})");
  private static final Pattern LEGACY_FORMAT = Pattern.compile("JS-([0-9]{4}-[0-9]{2})-([0-9]{8})");

  private SettlementOrderNumbers() {}

  static int assignMissing(ObjectNode state) { return assignMissing(state, YearMonth.now(SHANGHAI)); }

  /** Call while holding the state row lock, reserving internal IDs and assigned business numbers. */
  static int assignMissing(ObjectNode state, YearMonth fallbackMonth) {
    BigInteger maximum = BigInteger.ZERO;
    var used = new HashMap<String, JsonNode>();
    var missing = new ArrayList<ObjectNode>();
    var adopting = new ArrayList<ObjectNode>();
    for (var order : state.path("orders")) {
      String id = order.path("id").asText();
      boolean shortId = id.length() <= 191 && OrderNumberService.isShortNumber(id);
      require(!id.startsWith("N") || shortId, "订单短编号格式无效");
      reserve(used, id, order);
      var idMatch = FORMAT.matcher(id);
      boolean standardId = idMatch.matches();
      if (standardId) maximum = maximum.max(new BigInteger(idMatch.group(2)));
      String number = order.path("orderNumber").asText();
      require(!order.has("orderNumber") || order.path("orderNumber").isTextual(), "订单业务编号格式无效");
      boolean settlement = JushuitanDocumentService.isSettlement(order);
      if (number.startsWith("N")) {
        require(number.length() <= 191 && OrderNumberService.isShortNumber(number), "订单短编号格式无效");
        reserve(used, number, order);
        continue;
      }
      var legacy = LEGACY_FORMAT.matcher(number);
      if (number.isEmpty() || legacy.matches()) {
        require(number.isEmpty() || settlement, "仅结算补录可迁移旧业务编号");
        if (settlement && number.isEmpty() && (shortId || standardId && validMonth(idMatch.group(1))
            && new BigInteger(idMatch.group(2)).signum() > 0)) {
          adopting.add((ObjectNode) order);
        } else if (settlement) {
          if (!number.isEmpty()) require(validMonth(legacy.group(1)), "订单编号月份无效");
          missing.add((ObjectNode) order);
        }
        continue;
      }
      require(settlement, "普通订单沿用现有订单编号");
      var match = FORMAT.matcher(number);
      require(match.matches() && number.length() <= 191, "订单业务编号格式无效");
      require(validMonth(match.group(1)), "订单编号月份无效");
      var sequence = new BigInteger(match.group(2));
      require(sequence.signum() > 0, "订单业务编号流水无效");
      reserve(used, number, order);
      maximum = maximum.max(sequence);
    }
    missing.sort(Comparator.comparing(SettlementOrderNumbers::createdAt).thenComparing(o -> o.path("id").asText()));
    for (var order : missing) {
      Instant created = createdAt(order);
      var month = created.equals(Instant.MAX) ? fallbackMonth : YearMonth.from(created.atZone(SHANGHAI));
      maximum = maximum.add(BigInteger.ONE);
      String digits = maximum.toString();
      String number = month + "-" + "0".repeat(Math.max(0, 8 - digits.length())) + digits;
      require(number.length() <= 191, "订单编号流水已超出范围");
      reserve(used, number, order);
      order.put("orderNumber", number);
    }
    for (var order : adopting) order.put("orderNumber", order.path("id").asText());
    return missing.size() + adopting.size();
  }

  private static boolean validMonth(String month) {
    try { YearMonth.parse(month); return true; }
    catch (DateTimeParseException failure) { return false; }
  }

  private static void reserve(Map<String, JsonNode> used, String number, JsonNode owner) {
    var previous = used.putIfAbsent(number, owner);
    if (previous != null && previous != owner)
      throw new com.emie.crm.common.exception.ApiException(409, "订单编号已被其他订单使用，请刷新后重新创建");
  }

  static void protect(JsonNode old, ObjectNode next, boolean restoring) {
    var previous = new HashMap<String, JsonNode>();
    old.path("orders").forEach(order -> previous.put(order.path("id").asText(), order));
    for (var order : next.path("orders")) {
      var prior = previous.get(order.path("id").asText());
      var supplied = order.path("orderNumber");
      String number = prior == null ? "" : prior.path("orderNumber").asText();
      if (!number.isEmpty()) {
        if (restoring && supplied.isMissingNode()) ((ObjectNode) order).put("orderNumber", number);
        else require(supplied.isTextual() && number.equals(supplied.asText()), "订单业务编号由系统分配，不能修改或移除");
      } else if (!restoring) {
        require(supplied.isMissingNode() || supplied.isTextual() && supplied.asText().isEmpty(), "订单业务编号由系统分配，不能手工设置");
      }
    }
  }

  private static Instant createdAt(JsonNode order) {
    String value = order.path("created").asText();
    try { return OffsetDateTime.parse(value).toInstant(); }
    catch (DateTimeParseException ignored) { }
    try { return LocalDateTime.parse(value.replace(' ', 'T')).atZone(SHANGHAI).toInstant(); }
    catch (DateTimeParseException ignored) { }
    try { return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(SHANGHAI).toInstant(); }
    catch (DateTimeParseException ignored) { return Instant.MAX; }
  }
}
