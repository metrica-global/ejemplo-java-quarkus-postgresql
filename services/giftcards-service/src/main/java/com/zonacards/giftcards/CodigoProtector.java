package com.zonacards.giftcards;

/**
 * Port: protects a card code at rest. The plain code is never stored.
 *
 * <ul>
 *   <li>the ciphertext keeps the code recoverable for the flows that legitimately need it;
 *   <li>the blind index is a keyed hash of the code: it is deterministic, so the database can
 *       enforce that no two cards share a code, and find a card by its code, without the
 *       database ever seeing the code.
 * </ul>
 *
 * The adapter decides where the keys live (a secret, a KMS); the domain does not care.
 */
public interface CodigoProtector {

  /**
   * @param cifrado the encrypted code
   * @param indice the blind index
   * @param mascara the only representation that may be shown or logged
   */
  record Protegido(byte[] cifrado, byte[] indice, String mascara) {}

  Protegido proteger(String codigo);

  String revelar(byte[] cifrado);
}
