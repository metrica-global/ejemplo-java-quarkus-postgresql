package com.zonacards.kernel;

import java.util.UUID;

public record ClientId(String value) implements TypedId {

  public static ClientId of(String value) {
    return new ClientId(value == null ? "" : value.trim());
  }

  public static ClientId newId() {
    return new ClientId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
