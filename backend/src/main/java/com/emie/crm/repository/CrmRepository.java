package com.emie.crm.repository;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CrmRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CrmRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public JsonNode parse(String json) {
    try {
      return json == null ? NullNode.instance : mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("数据库JSON无效", e);
    }
  }

  public String encode(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("数据无法序列化", e);
    }
  }

  public JsonNode state(boolean lock) {
    var rows =
        jdbc.queryForList("SELECT payload FROM state WHERE id=1" + (lock ? " FOR UPDATE" : ""));
    return rows.isEmpty() ? NullNode.instance : parse((String) rows.getFirst().get("payload"));
  }

  public void state(JsonNode value) {
    jdbc.update("UPDATE state SET payload=? WHERE id=1", encode(value));
  }

  public List<JsonNode> documents(String table, boolean desc, int limit) {
    if (!Set.of("audit", "receipts").contains(table)) throw new IllegalArgumentException();
    return jdbc
        .queryForList(
            "SELECT payload FROM "
                + table
                + " ORDER BY seq "
                + (desc ? "DESC" : "ASC")
                + (limit > 0 ? " LIMIT " + limit : ""))
        .stream()
        .map(r -> parse((String) r.get("payload")))
        .toList();
  }

  public void receipt(JsonNode value) {
    jdbc.update(
        "INSERT INTO receipts(id,payload) VALUES(?,?) ON DUPLICATE KEY UPDATE"
            + " payload=VALUES(payload)",
        value.path("id").asText(),
        encode(value));
  }

  public void audit(String user, String action) {
    jdbc.update(
        "INSERT INTO audit(id,payload) VALUES(?,?)",
        UUID.randomUUID().toString(),
        encode(Map.of("user", user, "action", action, "time", now())));
  }

  public static String now() {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now());
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    for (var row : rows)
      row.replaceAll(
          (k, v) ->
              v instanceof Timestamp t
                  ? new DateTimeFormatterBuilder()
                      .appendInstant(3)
                      .toFormatter()
                      .format(t.toInstant())
                  : v);
    return rows;
  }

  public ObjectNode object() {
    return mapper.createObjectNode();
  }

  public ArrayNode array() {
    return mapper.createArrayNode();
  }

  public JsonNode tree(Object obj) {
    return mapper.valueToTree(obj);
  }
}
