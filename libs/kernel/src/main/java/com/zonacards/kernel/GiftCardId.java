package com.zonacards.kernel;

public record GiftCardId(String value) implements TypedId {

  public static GiftCardId of(String value) {
    return new GiftCardId(value == null ? "" : value.trim());
  }

  public static GiftCardId newId() {
    return new GiftCardId(Ids.uuidV7().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
