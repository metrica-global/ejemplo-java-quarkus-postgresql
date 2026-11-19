package com.zonacards.giftcards.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the giftcards domain understands of the events published by lotes: this consumer's own
 * view, with only the fields it uses. No Java code is shared with the producer; the contract is
 * the JSON documented in {@code docs/eventos}.
 */
public final class LotesEvents {

  public static final String LOTE_SOLICITADO = "zonacards.lotes.lote-solicitado.v1";
  public static final String LOTE_ANULADO = "zonacards.lotes.lote-anulado.v1";

  private LotesEvents() {}

  public record LoteSolicitado(String loteId, String pais, String moneda, BigDecimal monto, int cantidad, LocalDate vigenciaHasta) {}

  public record LoteAnulado(String loteId, String motivo) {}
}
