package com.zonacards.giftcards;

import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What this domain remembers about a lote it was asked to generate: how the work was split into
 * fragments and whether it may still continue. It is this domain's own record, built from the
 * event; the lote itself lives in another domain.
 */
public record Plan(
    LoteId loteId,
    TenantId tenantId,
    String pais,
    String moneda,
    BigDecimal monto,
    int cantidad,
    LocalDate vigenciaHasta,
    Estado estado,
    int totalFragmentos) {

  public enum Estado {
    /** Fragments may be generated. */
    EN_CURSO,
    /** The lote was annulled: no fragment may add cards any more. */
    ANULADO,
    /** A fragment failed for good: the rest is abandoned. */
    FALLIDO
  }

  /** One unit of work: the cards with sequence numbers {@code desdeSecuencia .. +cantidad-1}. */
  public record Fragmento(int numero, int desdeSecuencia, int cantidad) {}

  public static int totalFragmentos(int cantidad, int tamanoFragmento) {
    return (cantidad + tamanoFragmento - 1) / tamanoFragmento;
  }

  public static Plan nuevo(
      LoteId loteId, TenantId tenantId, String pais, String moneda, BigDecimal monto, int cantidad, LocalDate vigenciaHasta, int tamanoFragmento) {
    return new Plan(
        loteId, tenantId, pais, moneda, monto, cantidad, vigenciaHasta, Estado.EN_CURSO, totalFragmentos(cantidad, tamanoFragmento));
  }

  /** The fragment with the given number (1-based) when the lote is split by {@code tamanoFragmento}. */
  public Fragmento fragmento(int numero, int tamanoFragmento) {
    int desde = (numero - 1) * tamanoFragmento + 1;
    return new Fragmento(numero, desde, Math.min(tamanoFragmento, cantidad - desde + 1));
  }
}
