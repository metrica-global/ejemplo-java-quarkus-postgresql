package com.zonacards.lotes;

import com.zonacards.kernel.Validatable;
import com.zonacards.kernel.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Requests of the lotes use cases. Each one validates its own shape with plain code: no
 * annotations, no reflection, and the same rules apply whoever the caller is.
 */
public final class LoteRequests {

  public static final int CANTIDAD_MAXIMA = 200_000;
  private static final BigDecimal MONTO_MAXIMO = new BigDecimal("9999999.99");

  private LoteRequests() {}

  /**
   * @param pais ISO 3166-1 numeric, three digits; it becomes the prefix of every card code
   * @param moneda ISO 4217, three upper-case letters
   * @param cantidad how many gift cards to generate, up to {@value #CANTIDAD_MAXIMA}
   */
  public record CrearLoteRequest(
      String descripcion,
      String referenciaExterna,
      String pais,
      String moneda,
      BigDecimal monto,
      Integer cantidad,
      LocalDate vigenciaHasta)
      implements Validatable {

    @Override
    public void validate() {
      validarDescripcion(descripcion);
      validarReferencia(referenciaExterna);
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
      if (cantidad == null) {
        throw Validation.required("cantidad");
      }
      if (cantidad < 1 || cantidad > CANTIDAD_MAXIMA) {
        throw Validation.invalid("cantidad", "must be between 1 and " + CANTIDAD_MAXIMA);
      }
      if (vigenciaHasta == null) {
        throw Validation.required("vigenciaHasta");
      }
    }
  }

  /** Only descriptive data can change after a lote is requested. {@code version} guards lost updates. */
  public record ActualizarLoteRequest(String descripcion, String referenciaExterna, Integer version) implements Validatable {

    @Override
    public void validate() {
      validarDescripcion(descripcion);
      validarReferencia(referenciaExterna);
      if (version == null) {
        throw Validation.required("version");
      }
    }
  }

  private static void validarDescripcion(String descripcion) {
    if (descripcion == null || descripcion.isBlank()) {
      throw Validation.required("descripcion");
    }
    if (descripcion.length() > 200) {
      throw Validation.invalid("descripcion", "must be at most 200 characters");
    }
  }

  private static void validarReferencia(String referencia) {
    if (referencia != null && referencia.length() > 100) {
      throw Validation.invalid("referenciaExterna", "must be at most 100 characters");
    }
  }
}
