package com.zonacards.giftcards;

import com.zonacards.errx.ErrorCode;
import com.zonacards.errx.ErrorRegistry;
import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;
import com.zonacards.kernel.GiftCardId;

/** Every error the giftcards domain can produce, with a stable code. */
public final class GiftCardErrors {

  public static final ErrorRegistry REGISTRY = new ErrorRegistry("GIFTCARD");

  public static final ErrorCode NOT_FOUND = REGISTRY.register("NOT_FOUND", ErrorType.NOT_FOUND, "Gift card no encontrada");
  public static final ErrorCode VERSION_CONFLICT =
      REGISTRY.register("VERSION_CONFLICT", ErrorType.CONFLICT, "La gift card fue modificada por otra operación");
  public static final ErrorCode ESTADO_INVALIDO =
      REGISTRY.register("ESTADO_INVALIDO", ErrorType.BUSINESS, "La operación no es válida en el estado actual de la gift card");
  public static final ErrorCode VENCIDA = REGISTRY.register("VENCIDA", ErrorType.BUSINESS, "La gift card está vencida");
  public static final ErrorCode CODIGO_NO_DISPONIBLE =
      REGISTRY.register("CODIGO_NO_DISPONIBLE", ErrorType.INTERNAL, "No se pudo generar un código único");

  private GiftCardErrors() {}

  public static Errx notFound(GiftCardId id) {
    return REGISTRY.create(NOT_FOUND).withDetail("giftcard_id", id.value());
  }

  public static Errx versionConflict(GiftCardId id) {
    return REGISTRY.create(VERSION_CONFLICT).withDetail("giftcard_id", id.value());
  }

  public static Errx estadoInvalido(GiftCardEstado estado, String operacion) {
    return REGISTRY.create(ESTADO_INVALIDO).withDetail("estado", estado.name()).withDetail("operacion", operacion);
  }

  public static Errx vencida() {
    return REGISTRY.create(VENCIDA);
  }

  public static Errx codigoNoDisponible() {
    return REGISTRY.create(CODIGO_NO_DISPONIBLE);
  }
}
