package com.emie.crm.service;

import com.emie.crm.common.audit.AuditCompression;
import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Completed calendar quarters only; compressed payload and lookup pointers commit together. */
@Service
public class AuditArchiveService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private final CrmRepository db;
  private final TransactionTemplate tx;
  public AuditArchiveService(CrmRepository db, PlatformTransactionManager manager) {
    this.db = db; this.tx = new TransactionTemplate(manager);
  }
  static Instant quarterStart(Instant now) {
    var date = now.atZone(ZONE).toLocalDate();
    return LocalDate.of(date.getYear(), ((date.getMonthValue()-1)/3)*3+1, 1).atStartOfDay(ZONE).toInstant();
  }
  static String quarter(Instant time) {
    var date = time.atZone(ZONE);
    return date.getYear()+"-Q"+((date.getMonthValue()-1)/3+1);
  }
  @Scheduled(initialDelay = 60000, fixedDelay = 600000)
  public void scheduled() {
    try { for (int batch = 0; batch < 20 && archiveBatch(Instant.now()) > 0; batch++) {} }
    catch (Exception e) { LoggerFactory.getLogger(getClass()).error("季度日志归档失败，原记录保持不变，下次重试", e); }
  }
  int archiveBatch(Instant now) {
    return tx.execute(status -> {
      var cutoff = quarterStart(now);
      var rows = db.rows("SELECT seq,id,payload FROM audit WHERE JSON_EXTRACT(payload,'$._archive') IS NULL "
          + "AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.time')) < ? ORDER BY seq LIMIT 64 FOR UPDATE SKIP LOCKED", cutoff.toString());
      var quarters = new LinkedHashMap<String,List<Map<String,Object>>>();
      int budget = 0;
      for (var row : rows) {
        var payload = db.parse(row.get("payload").toString());
        Instant time;
        try { time = Instant.parse(payload.path("time").asText()); } catch (RuntimeException invalidTime) { continue; }
        if (!time.isBefore(cutoff)) continue;
        int size = row.get("payload").toString().getBytes(StandardCharsets.UTF_8).length;
        if (size > 32*1024*1024) continue; // Leave oversized legacy rows readable in their original form.
        if (budget + size > 32*1024*1024) break;
        budget += size;
        quarters.computeIfAbsent(quarter(time), q -> new ArrayList<>()).add(row);
      }
      int archived = 0;
      for (var group : quarters.entrySet()) {
        String archiveId = UUID.randomUUID().toString(); var entries = db.array();
        for (var row : group.getValue()) {
          var entry = entries.addObject().put("id", row.get("id").toString());
          entry.set("payload", db.parse(row.get("payload").toString()));
        }
        byte[] raw = db.encode(entries).getBytes(StandardCharsets.UTF_8);
        var packed = AuditCompression.compress(raw); String checksum = AuditCompression.sha256(raw);
        if (!Arrays.equals(raw, AuditCompression.restore(packed, checksum))) throw new IllegalStateException("归档往返校验失败");
        db.jdbc().update("INSERT INTO audit_archives(id,quarter,record_count,raw_bytes,compressed_bytes,sha256,content,created_at) VALUES(?,?,?,?,?,?,?,?)",
            archiveId, group.getKey(), entries.size(), raw.length, packed.length, checksum, packed, CrmRepository.now());
        int offset = 0;
        for (var row : group.getValue()) {
          var original = db.parse(row.get("payload").toString());
          var pointer = db.object().put("id", row.get("id").toString());
          for (String key : List.of("user", "action", "time")) if (original.has(key)) pointer.set(key, original.get(key));
          // Keep the order lookup index, without duplicating the large snapshots.
          if (original.path("details").has("orderId")) pointer.putObject("details").set("orderId", original.path("details").get("orderId"));
          pointer.putObject("_archive").put("id", archiveId).put("quarter", group.getKey()).put("index", offset++);
          int updated = db.jdbc().update("UPDATE audit SET payload=? WHERE seq=? AND id=?", db.encode(pointer), row.get("seq"), row.get("id"));
          if (updated != 1) throw new IllegalStateException("归档日志索引更新失败");
          archived++;
        }
      }
      return archived;
    });
  }
}
