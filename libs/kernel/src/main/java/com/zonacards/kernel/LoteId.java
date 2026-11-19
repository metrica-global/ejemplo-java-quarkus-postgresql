package com.zonacards.kernel;

public record LoteId(String value) implements TypedId {

  public static LoteId of(String value) {
    return new LoteId(value == null ? "" : value.trim());
  }

  public static LoteId newId() {
    return new LoteId(Ids.uuidV7().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
