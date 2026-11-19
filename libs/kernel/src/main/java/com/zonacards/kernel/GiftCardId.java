package com.zonacards.kernel;

import java.util.UUID;

public record GiftCardId(String value) implements TypedId {

  public static GiftCardId of(String value) {
    return new GiftCardId(value == null ? "" : value.trim());
  }

  public static GiftCardId newId() {
    return new GiftCardId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
