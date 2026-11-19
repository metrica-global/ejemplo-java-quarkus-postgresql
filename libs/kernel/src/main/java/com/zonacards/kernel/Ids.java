package com.zonacards.kernel;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Identifier generation. Ids are UUID version 7 (RFC 9562): the first 48 bits are the Unix time
 * in milliseconds, so new rows land at the end of the primary-key index instead of at random
 * pages. The remaining 74 bits come from a CSPRNG.
 */
public final class Ids {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Ids() {}

  public static UUID uuidV7() {
    return uuidV7(System.currentTimeMillis());
  }

  static UUID uuidV7(long epochMillis) {
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);
    long randA = ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);
    long msb = (epochMillis << 16) | 0x7000L | randA;
    long lsb = (ByteBuffer.wrap(random, 2, 8).getLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
