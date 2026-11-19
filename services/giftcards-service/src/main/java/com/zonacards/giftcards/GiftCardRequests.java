package com.zonacards.giftcards;

import com.zonacards.kernel.Validatable;
import com.zonacards.kernel.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Requests of the giftcards use cases, each validating its own shape with plain code. */
public final class GiftCardRequests {

  private static final BigDecimal MONTO_MAXIMO = new BigDecimal("9999999.99");

  private GiftCardRequests() {}

  /** Issues a single card, outside any lote. */
  public record EmitirGiftCardRequest(String pais, String moneda, BigDecimal monto, LocalDate vigenciaHasta) implements Validatable {

    @Override
    public void validate() {
      if (pais == null || !pais.matches("\\d{3}")) {
        throw Validation.invalid("pais", "must be an ISO 3166-1 numeric code (three digits)");
      }
      if (moneda == null || !moneda.matches("[A-Z]{3}")) {
        throw Validation.invalid("moneda", "must be an ISO 4217 code (three upper-case letters)");
      }
      if (monto == null) {
        throw Validation.required("monto");
      }
      if (monto.signum() <= 0 || monto.compareTo(MONTO_MAXIMO) > 0 || monto.stripTrailingZeros().scale() > 2) {
        throw Validation.invalid("monto", "must be positive, at most 9999999.99, with up to two decimals");
      }
      if (vigenciaHasta == null) {
        throw Validation.required("vigenciaHasta");
      }
    }
  }

  /** The only thing that can change on an issued card is how long it stays valid. */
  public record ExtenderVigenciaRequest(LocalDate vigenciaHasta, Integer version) implements Validatable {

    @Override
    public void validate() {
      if (vigenciaHasta == null) {
        throw Validation.required("vigenciaHasta");
      }
      if (version == null) {
        throw Validation.required("version");
      }
    }
  }
}
