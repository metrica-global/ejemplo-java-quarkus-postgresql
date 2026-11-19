package com.zonacards.kernel;

import java.util.UUID;

public record TenantId(String value) implements TypedId {

  public static TenantId of(String value) {
    return new TenantId(value == null ? "" : value.trim());
  }

  public static TenantId newId() {
    return new TenantId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
