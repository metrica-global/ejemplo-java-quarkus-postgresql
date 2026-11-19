package com.zonacards.giftcards;

/**
 * Lifecycle of a gift card.
 *
 * <pre>
 * GENERADA ──▶ ACTIVA
 *     │           │
 *     └───────────┴──▶ ANULADA
 * </pre>
 */
public enum GiftCardEstado {
  GENERADA,
  ACTIVA,
  ANULADA
}
