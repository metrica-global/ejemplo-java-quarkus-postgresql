package com.zonacards.lotes.events;

/**
 * What the lotes domain understands of the events published by giftcards. These records are
 * this consumer's own view: only the fields it uses, read tolerantly. There is deliberately no
 * shared library of event classes between the two domains; the contract is the JSON documented
 * in {@code docs/eventos}, not Java code.
 */
public final class GiftcardsEvents {

  public static final String GENERACION_INICIADA = "zonacards.giftcards.generacion-iniciada.v1";
  public static final String FRAGMENTO_GENERADO = "zonacards.giftcards.fragmento-generado.v1";
  public static final String FRAGMENTO_FALLIDO = "zonacards.giftcards.fragmento-fallido.v1";
  public static final String LOTE_ANULADO_APLICADO = "zonacards.giftcards.lote-anulado-aplicado.v1";

  private GiftcardsEvents() {}

  public record GeneracionIniciada(String loteId, int totalFragmentos) {}

  public record FragmentoGenerado(String loteId, int fragmento, int totalFragmentos, int cantidad) {}

  public record FragmentoFallido(String loteId, int fragmento, String motivo) {}

  public record LoteAnuladoAplicado(String loteId, int giftcardsAnuladas) {}
}
