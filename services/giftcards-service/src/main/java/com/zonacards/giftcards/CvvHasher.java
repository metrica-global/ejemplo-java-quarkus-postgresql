package com.zonacards.giftcards;

/**
 * Port: one-way protection of the CVV. A CVV is short, so a fast hash would be reversed by trying
 * every value; the adapter must use a deliberately expensive, salted password hash. That cost is
 * also why generating a large lote takes real CPU time.
 */
public interface CvvHasher {

  String hash(String cvv);

  boolean verificar(String cvv, String hash);
}
