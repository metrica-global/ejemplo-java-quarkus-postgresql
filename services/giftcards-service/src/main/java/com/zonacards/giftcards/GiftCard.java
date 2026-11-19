package com.zonacards.giftcards;

import com.zonacards.giftcards.CodigoProtector.Protegido;
import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The gift card aggregate. It never holds its code or its CVV in the clear: only the encrypted
 * code, a blind index, a mask and the hash of the CVV.
 *
 * <p>A card either belongs to a lote (then it has a sequence number inside it) or was issued on
 * its own.
 */
public final class GiftCard {

  private final GiftCardId id;
  private final TenantId tenantId;
  private final LoteId loteId;
  private final Integer secuencia;
  private final String pais;
  private final String moneda;
  private final BigDecimal monto;
  private LocalDate vigenciaHasta;
  private GiftCardEstado estado;
  private final byte[] codigoCifrado;
  private final byte[] codigoIndice;
  private final String codigoMascara;
  private final String cvvHash;
  private String motivo;
  private Instant activadaEn;
  private Instant anuladaEn;
  private final Instant creadoEn;
  private Instant actualizadoEn;
  private final int version;

  private GiftCard(
      GiftCardId id,
      TenantId tenantId,
      LoteId loteId,
      Integer secuencia,
      String pais,
      String moneda,
      BigDecimal monto,
      LocalDate vigenciaHasta,
      GiftCardEstado estado,
      byte[] codigoCifrado,
      byte[] codigoIndice,
      String codigoMascara,
      String cvvHash,
      String motivo,
      Instant activadaEn,
      Instant anuladaEn,
      Instant creadoEn,
      Instant actualizadoEn,
      int version) {
    this.id = id;
    this.tenantId = tenantId;
    this.loteId = loteId;
    this.secuencia = secuencia;
    this.pais = pais;
    this.moneda = moneda;
    this.monto = monto;
    this.vigenciaHasta = vigenciaHasta;
    this.estado = estado;
    this.codigoCifrado = codigoCifrado;
    this.codigoIndice = codigoIndice;
    this.codigoMascara = codigoMascara;
    this.cvvHash = cvvHash;
    this.motivo = motivo;
    this.activadaEn = activadaEn;
    this.anuladaEn = anuladaEn;
    this.creadoEn = creadoEn;
    this.actualizadoEn = actualizadoEn;
    this.version = version;
  }

  /**
   * A new card in GENERADA.
   *
   * @param loteId null for a card issued on its own
   * @param secuencia position inside the lote, starting at 1; null without a lote
   */
  public static GiftCard nueva(
      TenantId tenantId,
      LoteId loteId,
      Integer secuencia,
      String pais,
      String moneda,
      BigDecimal monto,
      LocalDate vigenciaHasta,
      Protegido codigo,
      String cvvHash,
      Instant now) {
    return new GiftCard(
        GiftCardId.newId(), tenantId, loteId, secuencia, pais, moneda, monto.setScale(2), vigenciaHasta, GiftCardEstado.GENERADA,
        codigo.cifrado(), codigo.indice(), codigo.mascara(), cvvHash, null, null, null, now, now, 0);
  }

  /** Rebuilds a card from storage. Only repositories call it. */
  public static GiftCard restore(
      GiftCardId id,
      TenantId tenantId,
      LoteId loteId,
      Integer secuencia,
      String pais,
      String moneda,
      BigDecimal monto,
      LocalDate vigenciaHasta,
      GiftCardEstado estado,
      byte[] codigoCifrado,
      byte[] codigoIndice,
      String codigoMascara,
      String cvvHash,
      String motivo,
      Instant activadaEn,
      Instant anuladaEn,
      Instant creadoEn,
      Instant actualizadoEn,
      int version) {
    return new GiftCard(
        id, tenantId, loteId, secuencia, pais, moneda, monto, vigenciaHasta, estado, codigoCifrado, codigoIndice, codigoMascara,
        cvvHash, motivo, activadaEn, anuladaEn, creadoEn, actualizadoEn, version);
  }

  /** Activates the card. Returns false when it was already active. */
  public boolean activar(LocalDate hoy, Instant now) {
    if (estado == GiftCardEstado.ANULADA) {
      throw GiftCardErrors.estadoInvalido(estado, "activar");
    }
    if (vigenciaHasta.isBefore(hoy)) {
      throw GiftCardErrors.vencida();
    }
    if (estado == GiftCardEstado.ACTIVA) {
      return false;
    }
    this.estado = GiftCardEstado.ACTIVA;
    this.activadaEn = now;
    this.actualizadoEn = now;
    return true;
  }

  /** Annuls the card. Returns false when it was already annulled. */
  public boolean anular(String motivo, Instant now) {
    if (estado == GiftCardEstado.ANULADA) {
      return false;
    }
    this.estado = GiftCardEstado.ANULADA;
    this.motivo = motivo;
    this.anuladaEn = now;
    this.actualizadoEn = now;
    return true;
  }

  /** Validity can only move forward, and not on an annulled card. */
  public void extenderVigencia(LocalDate nueva, Instant now) {
    if (estado == GiftCardEstado.ANULADA) {
      throw GiftCardErrors.estadoInvalido(estado, "extender vigencia");
    }
    if (!nueva.isAfter(vigenciaHasta)) {
      throw Validation.invalid("vigenciaHasta", "must be later than the current date of the card");
    }
    this.vigenciaHasta = nueva;
    this.actualizadoEn = now;
  }

  public GiftCardId id() {
    return id;
  }

  public TenantId tenantId() {
    return tenantId;
  }

  public LoteId loteId() {
    return loteId;
  }

  public Integer secuencia() {
    return secuencia;
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

  public LocalDate vigenciaHasta() {
    return vigenciaHasta;
  }

  public GiftCardEstado estado() {
    return estado;
  }

  public byte[] codigoCifrado() {
    return codigoCifrado;
  }

  public byte[] codigoIndice() {
    return codigoIndice;
  }

  public String codigoMascara() {
    return codigoMascara;
  }

  public String cvvHash() {
    return cvvHash;
  }

  public String motivo() {
    return motivo;
  }

  public Instant activadaEn() {
    return activadaEn;
  }

  public Instant anuladaEn() {
    return anuladaEn;
  }

  public Instant creadoEn() {
    return creadoEn;
  }

  public Instant actualizadoEn() {
    return actualizadoEn;
  }

  public int version() {
    return version;
  }
}
