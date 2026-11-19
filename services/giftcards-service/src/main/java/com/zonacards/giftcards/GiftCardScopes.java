package com.zonacards.giftcards;

/** OAuth scopes of the giftcards API. A token with {@code giftcards:*} holds all of them. */
public final class GiftCardScopes {

  public static final String READ = "giftcards:read";
  public static final String WRITE = "giftcards:write";
  public static final String ACTIVAR = "giftcards:activar";
  public static final String ANULAR = "giftcards:anular";

  private GiftCardScopes() {}
}
