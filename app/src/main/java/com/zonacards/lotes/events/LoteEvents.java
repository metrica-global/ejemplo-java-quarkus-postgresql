package com.zonacards.lotes.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The public contract of the lotes domain: the events it publishes to the topic
 * {@code lotes-events}. Payloads are facts in the past tense, and they carry what a consumer
 * needs to act without calling back.
 *
 * <p>Adding a field is compatible. Removing or renaming one requires a new type with the next
 * major version ({@code .v2}), published alongside the old one until consumers have moved.
 */
public final class LoteEvents {

  public static final String TOPIC = "lotes-events";

  public static final String SOLICITADO = "zonacards.lotes.lote-solicitado.v1";
  public static final String ANULADO = "zonacards.lotes.lote-anulado.v1";
  public static final String GENERADO = "zonacards.lotes.lote-generado.v1";
  public static final String FALLIDO = "zonacards.lotes.lote-fallido.v1";

  private LoteEvents() {}

  public record LoteSolicitado(
      String loteId, String pais, String moneda, BigDecimal monto, int cantidad, LocalDate vigenciaHasta) {}

  public record LoteAnulado(String loteId, String motivo) {}

  public record LoteGenerado(String loteId, int cantidad) {}

  public record LoteFallido(String loteId, String motivo) {}
}
