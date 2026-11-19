package com.zonacards.lotes;

import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.Validation;
import com.zonacards.lotes.LoteRequests.CrearLoteRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The lote aggregate: a request to generate a batch of gift cards, and the record of how that
 * generation went. It owns its lifecycle; the gift cards themselves belong to another domain,
 * and this one only learns about them through events.
 *
 * <p>The methods that react to events are written for the delivery the broker really gives:
 * at least once and in any order. Each one says whether it changed anything, so the caller knows
 * when a transition happened for the first time.
 */
public final class Lote {

  /** Result of recording a generated fragment. */
  public enum Progreso {
    /** Counted; more fragments are expected. */
    REGISTRADO,
    /** Counted, and it was the last one: the lote is now GENERADO. */
    COMPLETADO,
    /** The lote is already in a terminal state; the event is acknowledged and ignored. */
    TARDIO
  }

  private final LoteId id;
  private final TenantId tenantId;
  private String descripcion;
  private String referenciaExterna;
  private final String pais;
  private final String moneda;
  private final BigDecimal monto;
  private final int cantidad;
  private final LocalDate vigenciaHasta;
  private LoteEstado estado;
  private int fragmentosTotales;
  private int fragmentosCompletados;
  private int generadas;
  private String motivo;
  private Instant anulacionAplicadaEn;
  private Integer giftcardsAnuladas;
  private final String idempotencyKey;
  private final String requestHash;
  private final String creadoPor;
  private final Instant creadoEn;
  private Instant actualizadoEn;
  private final int version;

  private Lote(
      LoteId id,
      TenantId tenantId,
      String descripcion,
      String referenciaExterna,
      String pais,
      String moneda,
      BigDecimal monto,
      int cantidad,
      LocalDate vigenciaHasta,
      LoteEstado estado,
      int fragmentosTotales,
      int fragmentosCompletados,
      int generadas,
      String motivo,
      Instant anulacionAplicadaEn,
      Integer giftcardsAnuladas,
      String idempotencyKey,
      String requestHash,
      String creadoPor,
      Instant creadoEn,
      Instant actualizadoEn,
      int version) {
    this.id = id;
    this.tenantId = tenantId;
    this.descripcion = descripcion;
    this.referenciaExterna = referenciaExterna;
    this.pais = pais;
    this.moneda = moneda;
    this.monto = monto;
    this.cantidad = cantidad;
    this.vigenciaHasta = vigenciaHasta;
    this.estado = estado;
    this.fragmentosTotales = fragmentosTotales;
    this.fragmentosCompletados = fragmentosCompletados;
    this.generadas = generadas;
    this.motivo = motivo;
    this.anulacionAplicadaEn = anulacionAplicadaEn;
    this.giftcardsAnuladas = giftcardsAnuladas;
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
    this.creadoPor = creadoPor;
    this.creadoEn = creadoEn;
    this.actualizadoEn = actualizadoEn;
    this.version = version;
  }

  /** A new lote, in SOLICITADO. The request must already be validated. */
  public static Lote solicitar(
      TenantId tenantId, CrearLoteRequest request, String idempotencyKey, String requestHash, String creadoPor, Instant now) {
    if (!request.vigenciaHasta().isAfter(LocalDate.ofInstant(now, ZoneOffset.UTC))) {
      throw Validation.invalid("vigenciaHasta", "must be a future date");
    }
    return new Lote(
        LoteId.newId(),
        tenantId,
        request.descripcion().trim(),
        request.referenciaExterna(),
        request.pais(),
        request.moneda(),
        request.monto().setScale(2),
        request.cantidad(),
        request.vigenciaHasta(),
        LoteEstado.SOLICITADO,
        0,
        0,
        0,
        null,
        null,
        null,
        idempotencyKey,
        requestHash,
        creadoPor,
        now,
        now,
        0);
  }

  /** Rebuilds a lote from storage. Only repositories call it. */
  public static Lote restore(
      LoteId id,
      TenantId tenantId,
      String descripcion,
      String referenciaExterna,
      String pais,
      String moneda,
      BigDecimal monto,
      int cantidad,
      LocalDate vigenciaHasta,
      LoteEstado estado,
      int fragmentosTotales,
      int fragmentosCompletados,
      int generadas,
      String motivo,
      Instant anulacionAplicadaEn,
      Integer giftcardsAnuladas,
      String idempotencyKey,
      String requestHash,
      String creadoPor,
      Instant creadoEn,
      Instant actualizadoEn,
      int version) {
    return new Lote(
        id, tenantId, descripcion, referenciaExterna, pais, moneda, monto, cantidad, vigenciaHasta, estado,
        fragmentosTotales, fragmentosCompletados, generadas, motivo, anulacionAplicadaEn, giftcardsAnuladas,
        idempotencyKey, requestHash, creadoPor, creadoEn, actualizadoEn, version);
  }

  public void actualizarMetadatos(String descripcion, String referenciaExterna, Instant now) {
    if (estado == LoteEstado.ANULADO) {
      throw LoteErrors.estadoInvalido(estado, "actualizar");
    }
    this.descripcion = descripcion.trim();
    this.referenciaExterna = referenciaExterna;
    this.actualizadoEn = now;
  }

  /**
   * Annuls the lote. Allowed in every state: before, during or after the generation. Returns
   * false when it was already annulled, so the caller does not announce it twice.
   */
  public boolean anular(String motivo, Instant now) {
    if (estado == LoteEstado.ANULADO) {
      return false;
    }
    this.estado = LoteEstado.ANULADO;
    this.motivo = motivo;
    this.actualizadoEn = now;
    return true;
  }

  /** The other domain confirmed it annulled the gift cards of this lote. */
  public void confirmarAnulacion(int giftcardsAnuladas, Instant now) {
    if (estado != LoteEstado.ANULADO) {
      return;
    }
    this.anulacionAplicadaEn = now;
    this.giftcardsAnuladas = giftcardsAnuladas;
    this.actualizadoEn = now;
  }

  /** The other domain planned the generation. May arrive after the first fragments. */
  public boolean iniciarGeneracion(int totalFragmentos, Instant now) {
    if (estado != LoteEstado.SOLICITADO) {
      return false;
    }
    this.estado = LoteEstado.EN_GENERACION;
    this.fragmentosTotales = totalFragmentos;
    this.actualizadoEn = now;
    return true;
  }

  /**
   * Counts a fragment that is known to be new for this lote (the caller deduplicates by fragment
   * number). Works even if "generation started" has not arrived yet: the fragment event carries
   * the total.
   */
  public Progreso registrarFragmento(int totalFragmentos, int cantidadGenerada, Instant now) {
    if (estado.terminal()) {
      return Progreso.TARDIO;
    }
    this.estado = LoteEstado.EN_GENERACION;
    this.fragmentosTotales = totalFragmentos;
    this.fragmentosCompletados++;
    this.generadas += cantidadGenerada;
    this.actualizadoEn = now;
    if (fragmentosCompletados >= fragmentosTotales) {
      this.estado = LoteEstado.GENERADO;
      return Progreso.COMPLETADO;
    }
    return Progreso.REGISTRADO;
  }

  /** The generation cannot be completed. Returns false when the lote was already terminal. */
  public boolean fallar(String motivo, Instant now) {
    if (estado.terminal()) {
      return false;
    }
    this.estado = LoteEstado.FALLIDO;
    this.motivo = motivo;
    this.actualizadoEn = now;
    return true;
  }

  public LoteId id() {
    return id;
  }

  public TenantId tenantId() {
    return tenantId;
  }

  public String descripcion() {
    return descripcion;
  }

  public String referenciaExterna() {
    return referenciaExterna;
  }

  public String pais() {
    return pais;
  }

  public String moneda() {
    return moneda;
  }

  public BigDecimal monto() {
    return monto;
  }

  public int cantidad() {
    return cantidad;
  }

  public LocalDate vigenciaHasta() {
    return vigenciaHasta;
  }

  public LoteEstado estado() {
    return estado;
  }

  public int fragmentosTotales() {
    return fragmentosTotales;
  }

  public int fragmentosCompletados() {
    return fragmentosCompletados;
  }

  public int generadas() {
    return generadas;
  }

  public String motivo() {
    return motivo;
  }

  public Instant anulacionAplicadaEn() {
    return anulacionAplicadaEn;
  }

  public Integer giftcardsAnuladas() {
    return giftcardsAnuladas;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public String requestHash() {
    return requestHash;
  }

  public String creadoPor() {
    return creadoPor;
  }

  public Instant creadoEn() {
    return creadoEn;
  }

  public Instant actualizadoEn() {
    return actualizadoEn;
  }

  /** Version read from storage; the repository increments it on every update. */
  public int version() {
    return version;
  }
}
