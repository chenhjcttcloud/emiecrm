package com.emie.crm.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordCodecTest {
  @Test
  void compatibleWithExistingNodeScryptHashes() {
    var codec = new PasswordCodec();
    String expected =
        "b0bfa6571955d31c6e6124ef2886e455d68128069e88c6429c6c65ba9da993c4991a3718dfd4cbe04a47cffe000a8c5c7579b2349b0a7e1ed3ae6af22afa9395";
    assertEquals(expected, codec.hash("crm-test-password", "test-uuid-salt"));
    assertTrue(codec.matches("crm-test-password", "test-uuid-salt", expected));
    assertFalse(codec.matches("wrong-password", "test-uuid-salt", expected));
  }
}
