package com.zonacards.kernel;

import java.util.UUID;

public record LoteId(String value) implements TypedId {

  public static LoteId of(String value) {
    return new LoteId(value == null ? "" : value.trim());
  }

  public static LoteId newId() {
    return new LoteId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
