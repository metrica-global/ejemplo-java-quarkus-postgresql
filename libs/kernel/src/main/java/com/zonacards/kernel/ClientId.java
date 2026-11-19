package com.zonacards.kernel;

public record ClientId(String value) implements TypedId {

  public static ClientId of(String value) {
    return new ClientId(value == null ? "" : value.trim());
  }

  public static ClientId newId() {
    return new ClientId(Ids.uuidV7().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
