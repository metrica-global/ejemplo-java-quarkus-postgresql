package com.zonacards.giftcards.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zonacards.giftcards.GiftCard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The gift card as the API shows it: masked code, never the code, the CVV, the ciphertext or
 * the hashes. {@code codigo} and {@code cvv} are filled only in the response that issues a card.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GiftCardResponse(
    String id,
    String loteId,
    Integer secuencia,
    String pais,
    String moneda,
    BigDecimal monto,
    LocalDate vigenciaHasta,
    String estado,
    String codigoEnmascarado,
    String codigo,
    String cvv,
    String motivo,
    Instant activadaEn,
    Instant anuladaEn,
    Instant creadoEn,
    Instant actualizadoEn,
    int version) {

  public static GiftCardResponse of(GiftCard card) {
    return of(card, null, null);
  }

  /** Only for the response of an emission: the one time the secrets are shown. */
  public static GiftCardResponse of(GiftCard card, String codigo, String cvv) {
    return new GiftCardResponse(
        card.id().value(),
        card.loteId() == null ? null : card.loteId().value(),
        card.secuencia(),
        card.pais(),
        card.moneda(),
        card.monto(),
        card.vigenciaHasta(),
        card.estado().name(),
        card.codigoMascara(),
        codigo,
        cvv,
        card.motivo(),
        card.activadaEn(),
        card.anuladaEn(),
        card.creadoEn(),
        card.actualizadoEn(),
        card.version());
  }
}
