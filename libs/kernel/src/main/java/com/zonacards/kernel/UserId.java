package com.zonacards.kernel;

import java.util.UUID;

public record UserId(String value) implements TypedId {

  public static UserId of(String value) {
    return new UserId(value == null ? "" : value.trim());
  }

  public static UserId newId() {
    return new UserId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
