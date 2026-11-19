package com.zonacards.lotes;

import com.zonacards.kernel.Actor;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UserId;
import com.zonacards.lotes.LoteRequests.CrearLoteRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

public final class Fixtures {

  public static final TenantId PE = TenantId.of("pe");
  public static final TenantId EC = TenantId.of("ec");
  public static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private Fixtures() {}

  public static AuthContext user(TenantId tenant, String... scopes) {
    return new AuthContext(Actor.user(UserId.of("ana")), tenant, "Ana", List.of(scopes));
  }

  public static CrearLoteRequest solicitud(int cantidad) {
    return new CrearLoteRequest(
        "Campaña de fin de año", "OC-2026-118", "604", "PEN", new BigDecimal("50.00"), cantidad, LocalDate.parse("2027-12-31"));
  }
}
