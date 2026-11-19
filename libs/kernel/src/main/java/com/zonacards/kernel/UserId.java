package com.zonacards.kernel;

public record UserId(String value) implements TypedId {

  public static UserId of(String value) {
    return new UserId(value == null ? "" : value.trim());
  }

  public static UserId newId() {
    return new UserId(Ids.uuidV7().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
