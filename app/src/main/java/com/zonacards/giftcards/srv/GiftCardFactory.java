package com.zonacards.giftcards.srv;

import com.zonacards.giftcards.CodigoGenerator;
import com.zonacards.giftcards.CodigoProtector;
import com.zonacards.giftcards.CvvHasher;
import com.zonacards.giftcards.GiftCard;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.random.RandomGenerator;

/**
 * Creates gift cards with their secrets. This is the expensive step: every card costs one
 * password hash of its CVV, by design. The plain code and CVV exist only in the returned value;
 * what goes into the card is already protected.
 */
public final class GiftCardFactory {

  /** A new card together with the secrets that will never be readable again. */
  public record Fabricada(GiftCard card, String codigo, String cvv) {}

  private final CodigoProtector protector;
  private final CvvHasher hasher;
  private final RandomGenerator random;

  /** @param random must be a CSPRNG in production */
  public GiftCardFactory(CodigoProtector protector, CvvHasher hasher, RandomGenerator random) {
    this.protector = protector;
    this.hasher = hasher;
    this.random = random;
  }

  public Fabricada crear(
      TenantId tenantId, LoteId loteId, Integer secuencia, String pais, String moneda, BigDecimal monto, LocalDate vigenciaHasta, Instant now) {
    String codigo = CodigoGenerator.codigo(pais, random);
    String cvv = CodigoGenerator.cvv(random);
    GiftCard card =
        GiftCard.nueva(tenantId, loteId, secuencia, pais, moneda, monto, vigenciaHasta, protector.proteger(codigo), hasher.hash(cvv), now);
    return new Fabricada(card, codigo, cvv);
  }
}
