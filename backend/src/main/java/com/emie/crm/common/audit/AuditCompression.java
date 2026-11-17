package com.emie.crm.common.audit;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Lossless archive codec. A corrupt archive must fail visibly, never become an empty history. */
public final class AuditCompression {
  public static final int MAX_RAW_BYTES = 64 * 1024 * 1024;
  private AuditCompression() {}
  public static String sha256(byte[] raw) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  public static byte[] compress(byte[] raw) {
    if (raw.length > MAX_RAW_BYTES) throw new IllegalArgumentException("日志归档批次过大");
    try {
      var output = new ByteArrayOutputStream();
      try (var gzip = new GZIPOutputStream(output)) { gzip.write(raw); }
      return output.toByteArray();
    } catch (IOException e) { throw new IllegalStateException("日志压缩失败", e); }
  }
  public static byte[] restore(byte[] compressed, String checksum) {
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed)); var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192]; int count;
      while ((count = gzip.read(buffer)) != -1) {
        if (output.size() + count > MAX_RAW_BYTES) throw new IOException("归档超过解压上限");
        output.write(buffer, 0, count);
      }
      var raw = output.toByteArray();
      if (!sha256(raw).equals(checksum)) throw new IOException("归档校验失败");
      return raw;
    } catch (IOException e) { throw new IllegalStateException("日志归档损坏，无法读取，请恢复数据库备份", e); }
  }
}
