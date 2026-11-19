package com.zonacards.lotes.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zonacards.lotes.Lote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The lote as the API shows it. Internal fields (idempotency key, request hash) stay inside. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoteResponse(
    String id,
    String descripcion,
    String referenciaExterna,
    String pais,
    String moneda,
    BigDecimal monto,
    int cantidad,
    LocalDate vigenciaHasta,
    String estado,
    Progreso progreso,
    String motivo,
    Instant anulacionAplicadaEn,
    Integer giftcardsAnuladas,
    Instant creadoEn,
    Instant actualizadoEn,
    int version) {

  public record Progreso(int fragmentosTotales, int fragmentosCompletados, int generadas) {}

  public static LoteResponse of(Lote lote) {
    return new LoteResponse(
        lote.id().value(),
        lote.descripcion(),
        lote.referenciaExterna(),
        lote.pais(),
        lote.moneda(),
        lote.monto(),
        lote.cantidad(),
        lote.vigenciaHasta(),
        lote.estado().name(),
        new Progreso(lote.fragmentosTotales(), lote.fragmentosCompletados(), lote.generadas()),
        lote.motivo(),
        lote.anulacionAplicadaEn(),
        lote.giftcardsAnuladas(),
        lote.creadoEn(),
        lote.actualizadoEn(),
        lote.version());
  }
}
