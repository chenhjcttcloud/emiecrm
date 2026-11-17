package com.emie.crm.service;

import com.emie.crm.common.audit.AuditCompression;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuditArchiveServiceTest {
  @Test void calendarQuartersUseShanghaiMidnight() {
    assertEquals(Instant.parse("2026-09-30T16:00:00Z"), AuditArchiveService.quarterStart(Instant.parse("2026-10-23T02:00:00Z")));
    assertEquals("2026-Q3",AuditArchiveService.quarter(Instant.parse("2026-09-30T15:59:59.999Z")));
    assertEquals("2026-Q4",AuditArchiveService.quarter(Instant.parse("2026-09-30T16:00:00Z")));
    assertEquals(Instant.parse("2025-12-31T16:00:00Z"),AuditArchiveService.quarterStart(Instant.parse("2026-01-01T00:00:00Z")));
  }
  @Test void gzipRoundTripPreservesUnicodeAndAllSnapshots() {
    byte[] raw=("{\"备注\":\"金额修改前后 ¥100.01 → ¥120.01\"}\n".repeat(500)).getBytes(StandardCharsets.UTF_8);
    byte[] packed=AuditCompression.compress(raw);
    assertArrayEquals(raw,AuditCompression.restore(packed,AuditCompression.sha256(raw)));
    assertTrue(packed.length<raw.length/10);
  }
  @Test void corruptionAndWrongChecksumMustNotSilentlyReturnEmptyHistory() {
    byte[] raw="完整记录".getBytes(StandardCharsets.UTF_8);byte[] packed=AuditCompression.compress(raw);
    assertThrows(IllegalStateException.class,()->AuditCompression.restore(packed,"incorrect"));
    packed[packed.length-1]^=1;
    assertThrows(IllegalStateException.class,()->AuditCompression.restore(packed,AuditCompression.sha256(raw)));
  }
}
