package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Numbers are never recycled. Every allocation and claim follows the existing global state lock. */
@Service
public class OrderNumberService {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");
  private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private final CrmRepository db;
  private final TransactionTemplate transactions;
  private final Clock clock;

  @Autowired
  public OrderNumberService(CrmRepository db, PlatformTransactionManager manager) {
    this(db, manager, Clock.systemUTC());
  }

  OrderNumberService(CrmRepository db, PlatformTransactionManager manager, Clock clock) {
    this.db = db; this.transactions = new TransactionTemplate(manager); this.clock = clock;
  }

  public ObjectNode reserve(String requestId, Actor actor) {
    authorize(actor); request(requestId);
    return transactions.execute(transaction -> {
      var state = db.state(true);
      String number = allocate(db, state, requestId, null, actor, "ORDINARY", LocalDate.now(clock.withZone(SHANGHAI)));
      return db.object().put("orderNumber", number);
    });
  }

  /** The settlement caller already owns the state lock and its encompassing creation transaction. */
  static String reserveForSettlement(CrmRepository db, JsonNode state, String requestId, String orderId, Actor actor) {
    authorize(actor); request(requestId);
    require(orderId != null && orderId.equals("JS-" + requestId), "结算补录记录编号无效");
    return allocate(db, state, requestId, orderId, actor, "SETTLEMENT", LocalDate.now(SHANGHAI));
  }

  private static void authorize(Actor actor) {
    actor.require("business.read"); actor.require("business.write");
    require(actor.name() != null && !actor.name().isBlank() && actor.name().length() <= 191
        && actor.name().chars().noneMatch(Character::isISOControl), "订单编号预留人无效");
  }

  private static void request(String requestId) {
    require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,128}"), "订单编号请求须为16至128位字母、数字、下划线或短横线");
  }

  public static boolean isShortNumber(String value) {
    if (value == null || value.length() > 32 || !value.matches("N[0-9]{6}[0-9]{4,}")) return false;
    try {
      LocalDate.of(2000 + Integer.parseInt(value.substring(1, 3)), Integer.parseInt(value.substring(3, 5)),
          Integer.parseInt(value.substring(5, 7)));
      var sequence = new BigInteger(value.substring(7));
      return sequence.signum() > 0 && sequence.compareTo(MAX) <= 0;
    } catch (DateTimeException | NumberFormatException invalid) { return false; }
  }

  private static String allocate(CrmRepository db, JsonNode state, String requestId, String orderId,
      Actor actor, String scope, LocalDate day) {
    var existing = db.rows("SELECT order_number,order_id FROM order_number_reservations WHERE actor_name=? AND scope=? AND request_id=? FOR UPDATE",
        actor.name(), scope, requestId);
    if (!existing.isEmpty()) {
      String number = existing.getFirst().get("order_number").toString();
      require(isShortNumber(number) && (orderId == null ? number : orderId).equals(existing.getFirst().get("order_id").toString()),
          "订单编号预留记录与原申请不一致");
      return number;
    }
    if (orderId != null && !db.rows("SELECT order_number FROM order_number_reservations WHERE order_id=?", orderId).isEmpty())
      throw new ApiException(409, "该订单已有编号预留，请核对原申请");
    Long reservedMaximum = db.jdbc().queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM order_number_reservations WHERE number_day=?", Long.class, Date.valueOf(day));
    BigInteger maximum = BigInteger.valueOf(reservedMaximum == null ? 0 : reservedMaximum);
    String prefix = "N" + day.format(DAY);
    for (var order : state.path("orders")) for (String key : new String[]{"id", "orderNumber"}) {
      String number = order.path(key).asText();
      if (isShortNumber(number) && number.startsWith(prefix)) maximum = maximum.max(new BigInteger(number.substring(7)));
    }
    require(maximum.signum() >= 0 && maximum.compareTo(MAX) < 0, "当天订单编号流水已超出范围，请联系管理员");
    long sequence = maximum.longValueExact() + 1;
    String digits = Long.toString(sequence);
    String number = prefix + "0".repeat(Math.max(0, 4 - digits.length())) + digits;
    db.jdbc().update("INSERT INTO order_number_reservations(actor_name,scope,request_id,number_day,sequence_no,order_number,order_id,consumed) VALUES(?,?,?,?,?,?,?,?)",
        actor.name(), scope, requestId, Date.valueOf(day), sequence, number, orderId == null ? number : orderId, orderId != null);
    return number;
  }

  /** First persistence consumes a normal number in the same transaction as the order itself. */
  static void requireNewNumbers(CrmRepository db, JsonNode old, JsonNode next, Actor actor) {
    var previous = new HashMap<String, JsonNode>();
    old.path("orders").forEach(order -> previous.put(order.path("id").asText(), order));
    for (var order : next.path("orders")) {
      String id = order.path("id").asText();
      if (previous.containsKey(id) || JushuitanDocumentService.isSettlement(order) || !id.startsWith("N")) continue;
      require(isShortNumber(id), "订单编号格式无效，请重新预留编号");
      var rows = db.rows("SELECT actor_name,scope,order_id,consumed FROM order_number_reservations WHERE order_number=? FOR UPDATE", id);
      if (rows.size() != 1) throw new ApiException(409, "订单编号尚未预留，请重新创建");
      var reservation = rows.getFirst();
      if (!actor.name().equals(reservation.get("actor_name")) || !"ORDINARY".equals(reservation.get("scope"))
          || !id.equals(reservation.get("order_id")) || consumed(reservation))
        throw new ApiException(409, "订单编号已使用或不属于当前申请，请重新创建");
      require(db.jdbc().update("UPDATE order_number_reservations SET consumed=TRUE WHERE order_number=? AND consumed=FALSE", id) == 1,
          "订单编号已被其他申请使用，请刷新后重试");
    }
  }

  /** Restoring existing data must not reassign a reservation or make a consumed number reusable. */
  static void requireRestoredNumbers(CrmRepository db, JsonNode state) {
    for (var order : state.path("orders")) {
      String id = order.path("id").asText();
      var numbers = new HashSet<String>();
      for (String number : new String[]{id, order.path("orderNumber").asText()}) {
        if (!number.startsWith("N")) continue;
        require(isShortNumber(number), "备份中的订单编号格式无效");
        numbers.add(number);
      }
      if (numbers.size() > 1) throw new ApiException(409, "同一订单不能绑定两个不同的短编号，请核对备份");
      for (String number : numbers) {
        var rows = db.rows("SELECT order_id,consumed FROM order_number_reservations WHERE order_number=? FOR UPDATE", number);
        if (!rows.isEmpty()) {
          if (!id.equals(rows.getFirst().get("order_id"))) throw new ApiException(409, "备份中的订单编号已绑定其他订单，不能重新分配");
          if (!consumed(rows.getFirst())) db.jdbc().update("UPDATE order_number_reservations SET consumed=TRUE WHERE order_number=?", number);
          continue;
        }
        if (!db.rows("SELECT order_number FROM order_number_reservations WHERE order_id=? FOR UPDATE", id).isEmpty())
          throw new ApiException(409, "该订单已绑定其他短编号，不能从备份重新分配");
        LocalDate day = LocalDate.of(2000 + Integer.parseInt(number.substring(1, 3)), Integer.parseInt(number.substring(3, 5)),
            Integer.parseInt(number.substring(5, 7)));
        long sequence = new BigInteger(number.substring(7)).longValueExact();
        if (!db.rows("SELECT order_number FROM order_number_reservations WHERE number_day=? AND sequence_no=? FOR UPDATE", Date.valueOf(day), sequence).isEmpty())
          throw new ApiException(409, "备份中的订单编号与已有当日流水冲突，请核对原编号");
        try {
          db.jdbc().update("INSERT INTO order_number_reservations(actor_name,scope,request_id,number_day,sequence_no,order_number,order_id,consumed) VALUES(?,?,?,?,?,?,?,TRUE)",
              "SYSTEM_RESTORE", "RESTORED", restorationRequest(number), Date.valueOf(day), sequence, number, id);
        } catch (DataIntegrityViolationException conflict) {
          throw new ApiException(409, "备份中的订单编号已被其他记录使用，请核对原绑定");
        }
      }
    }
  }

  private static String restorationRequest(String number) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(number.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private static boolean consumed(Map<String, Object> reservation) {
    Object value = reservation.get("consumed");
    return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
  }
}
