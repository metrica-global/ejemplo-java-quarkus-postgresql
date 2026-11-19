package com.zonacards.giftcards.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The public contract of the giftcards domain: the events it publishes to the topic
 * {@code giftcards-events}.
 *
 * <p>Generating a lote announces one event per fragment, not one per card: two hundred thousand
 * "card generated" events would say nothing a consumer needs and would cost as much as the work
 * itself.
 */
public final class GiftcardsEvents {

  public static final String TOPIC = "giftcards-events";

  public static final String GENERACION_INICIADA = "zonacards.giftcards.generacion-iniciada.v1";
  public static final String FRAGMENTO_GENERADO = "zonacards.giftcards.fragmento-generado.v1";
  public static final String FRAGMENTO_FALLIDO = "zonacards.giftcards.fragmento-fallido.v1";
  public static final String LOTE_ANULADO_APLICADO = "zonacards.giftcards.lote-anulado-aplicado.v1";
  public static final String GIFTCARD_EMITIDA = "zonacards.giftcards.giftcard-emitida.v1";
  public static final String GIFTCARD_ACTIVADA = "zonacards.giftcards.giftcard-activada.v1";
  public static final String GIFTCARD_ANULADA = "zonacards.giftcards.giftcard-anulada.v1";

  private GiftcardsEvents() {}

  public record GeneracionIniciada(String loteId, int totalFragmentos) {}

  public record FragmentoGenerado(String loteId, int fragmento, int totalFragmentos, int cantidad) {}

  public record FragmentoFallido(String loteId, int fragmento, String motivo) {}

  public record LoteAnuladoAplicado(String loteId, int giftcardsAnuladas) {}

  public record GiftCardEmitida(String giftCardId, String pais, String moneda, BigDecimal monto, LocalDate vigenciaHasta) {}

  public record GiftCardActivada(String giftCardId) {}

  public record GiftCardAnulada(String giftCardId, String motivo) {}
}
