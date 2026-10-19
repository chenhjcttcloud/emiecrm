package com.emie.crm.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Component;

@Component
public class PasswordCodec {
  public String hash(String password, String salt) {
    return HexFormat.of()
        .formatHex(
            SCrypt.generate(
                password.getBytes(StandardCharsets.UTF_8),
                salt.getBytes(StandardCharsets.UTF_8),
                16384,
                8,
                1,
                64));
  }

  public boolean matches(String password, String salt, String expected) {
    return MessageDigest.isEqual(
        hash(password, salt).getBytes(StandardCharsets.US_ASCII),
        expected.getBytes(StandardCharsets.US_ASCII));
  }
}
