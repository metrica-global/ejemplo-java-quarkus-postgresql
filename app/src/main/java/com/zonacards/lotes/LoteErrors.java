package com.zonacards.lotes;

import com.zonacards.errx.ErrorCode;
import com.zonacards.errx.ErrorRegistry;
import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;
import com.zonacards.kernel.LoteId;

/** Every error the lotes domain can produce, with a stable code. Clients branch on the code. */
public final class LoteErrors {

  public static final ErrorRegistry REGISTRY = new ErrorRegistry("LOTE");

  public static final ErrorCode NOT_FOUND = REGISTRY.register("NOT_FOUND", ErrorType.NOT_FOUND, "Lote no encontrado");
  public static final ErrorCode IDEMPOTENCY_KEY_REQUIRED =
      REGISTRY.register("IDEMPOTENCY_KEY_REQUIRED", ErrorType.VALIDATION, "El encabezado Idempotency-Key es obligatorio");
  public static final ErrorCode IDEMPOTENCY_KEY_REUSED =
      REGISTRY.register(
          "IDEMPOTENCY_KEY_REUSED", ErrorType.CONFLICT, "La Idempotency-Key ya se usó con una solicitud distinta");
  public static final ErrorCode VERSION_CONFLICT =
      REGISTRY.register("VERSION_CONFLICT", ErrorType.CONFLICT, "El lote fue modificado por otra operación");
  public static final ErrorCode ESTADO_INVALIDO =
      REGISTRY.register("ESTADO_INVALIDO", ErrorType.BUSINESS, "La operación no es válida en el estado actual del lote");

  private LoteErrors() {}

  public static Errx notFound(LoteId id) {
    return REGISTRY.create(NOT_FOUND).withDetail("lote_id", id.value());
  }

  public static Errx idempotencyKeyRequired() {
    return REGISTRY.create(IDEMPOTENCY_KEY_REQUIRED);
  }

  public static Errx idempotencyKeyReused() {
    return REGISTRY.create(IDEMPOTENCY_KEY_REUSED);
  }

  public static Errx versionConflict(LoteId id) {
    return REGISTRY.create(VERSION_CONFLICT).withDetail("lote_id", id.value());
  }

  public static Errx estadoInvalido(LoteEstado estado, String operacion) {
    return REGISTRY.create(ESTADO_INVALIDO).withDetail("estado", estado.name()).withDetail("operacion", operacion);
  }
}
