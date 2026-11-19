package com.zonacards.kernel;

public record TenantId(String value) implements TypedId {

  public static TenantId of(String value) {
    return new TenantId(value == null ? "" : value.trim());
  }

  public static TenantId newId() {
    return new TenantId(Ids.uuidV7().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
