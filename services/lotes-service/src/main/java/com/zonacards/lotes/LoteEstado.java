package com.zonacards.lotes;

/**
 * Lifecycle of a lote.
 *
 * <pre>
 * SOLICITADO ──▶ EN_GENERACION ──▶ GENERADO
 *      │               │               │
 *      ├───────────────┼───────────────┴──▶ ANULADO   (by request, at any moment)
 *      └───────────────┴──▶ FALLIDO                   (generation cannot be completed)
 * </pre>
 */
public enum LoteEstado {
  SOLICITADO,
  EN_GENERACION,
  GENERADO,
  ANULADO,
  FALLIDO;

  /** No event about the generation can change a lote in a terminal state. */
  public boolean terminal() {
    return this == ANULADO || this == FALLIDO || this == GENERADO;
  }
}
