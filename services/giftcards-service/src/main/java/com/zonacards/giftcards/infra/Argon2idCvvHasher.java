package com.zonacards.giftcards.infra;

import com.zonacards.giftcards.CvvHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Hashes CVVs with Argon2id (RFC 9106), using Bouncy Castle. The result is the standard encoded
 * string, {@code $argon2id$v=19$m=...,t=...,p=...$salt$hash}: it carries its own parameters, so
 * the cost can be raised later and old hashes still verify.
 *
 * <p>The defaults follow the OWASP minimum for Argon2id (19 MiB of memory, 2 iterations, 1 lane).
 * Each hash takes tens of milliseconds of one core. Multiply by the cards of a lote and you get
 * the workload this service scales out for.
 */
public final class Argon2idCvvHasher implements CvvHasher {

  private static final int SALT_BYTES = 16;
  private static final int HASH_BYTES = 32;
  private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getDecoder();

  private final int memoryKib;
  private final int iterations;
  private final int parallelism;
  private final SecureRandom random = new SecureRandom();

  public Argon2idCvvHasher(int memoryKib, int iterations, int parallelism) {
    if (memoryKib < 8 * parallelism || iterations < 1 || parallelism < 1) {
      throw new IllegalArgumentException("invalid Argon2 parameters");
    }
    this.memoryKib = memoryKib;
    this.iterations = iterations;
    this.parallelism = parallelism;
  }

  @Override
  public String hash(String cvv) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    byte[] hash = compute(cvv, salt, memoryKib, iterations, parallelism, HASH_BYTES);
    return "$argon2id$v=19$m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism + "$" + ENCODER.encodeToString(salt) + "$"
        + ENCODER.encodeToString(hash);
  }

  @Override
  public boolean verificar(String cvv, String encoded) {
    try {
      String[] parts = encoded.split("\\$");
      if (parts.length != 6 || !parts[1].equals("argon2id") || !parts[2].equals("v=19")) {
        return false;
      }
      int m = 0;
      int t = 0;
      int p = 0;
      for (String parameter : parts[3].split(",")) {
        String[] pair = parameter.split("=");
        switch (pair[0]) {
          case "m" -> m = Integer.parseInt(pair[1]);
          case "t" -> t = Integer.parseInt(pair[1]);
          case "p" -> p = Integer.parseInt(pair[1]);
          default -> {
            return false;
          }
        }
      }
      byte[] salt = DECODER.decode(parts[4]);
      byte[] expected = DECODER.decode(parts[5]);
      return MessageDigest.isEqual(expected, compute(cvv, salt, m, t, p, expected.length));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static byte[] compute(String cvv, byte[] salt, int memoryKib, int iterations, int parallelism, int length) {
    Argon2Parameters parameters =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build();
    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(parameters);
    byte[] out = new byte[length];
    generator.generateBytes(cvv.getBytes(StandardCharsets.UTF_8), out);
    return out;
  }
}
