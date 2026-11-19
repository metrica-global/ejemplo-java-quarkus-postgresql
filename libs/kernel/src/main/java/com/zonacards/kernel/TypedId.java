package com.zonacards.kernel;

/**
 * An entity identifier with its own type, so a {@code LoteId} can never be passed where a
 * {@code GiftCardId} is expected. A typed id is not validation and not authorization: validate
 * the value at the boundary and always constrain queries by tenant.
 */
public interface TypedId {

  String value();

  default boolean isEmpty() {
    return value() == null || value().isBlank();
  }
}
