package com.zonacards.giftcards;

import com.zonacards.giftcards.infra.AesGcmCodigoProtector;
import com.zonacards.giftcards.infra.Argon2idCvvHasher;
import com.zonacards.kernel.Actor;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

public final class Fixtures {

  public static final TenantId PE = TenantId.of("pe");
  public static final TenantId EC = TenantId.of("ec");
  public static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** Test keys. Real ones are 32 random bytes kept in a secret store. */
  public static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
  public static final String HMAC_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

  private Fixtures() {}

  public static AuthContext user(TenantId tenant, String... scopes) {
    return new AuthContext(Actor.user(UserId.of("ana")), tenant, "Ana", List.of(scopes));
  }

  public static CodigoProtector protector() {
    return new AesGcmCodigoProtector(KEY, HMAC_KEY);
  }

  /** The real algorithm with a tiny cost, so tests take milliseconds. */
  public static CvvHasher fastHasher() {
    return new Argon2idCvvHasher(32, 1, 1);
  }
}
