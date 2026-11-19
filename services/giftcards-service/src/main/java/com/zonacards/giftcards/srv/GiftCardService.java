package com.zonacards.giftcards.srv;

import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.Outbox;
import com.zonacards.giftcards.GiftCard;
import com.zonacards.giftcards.GiftCardErrors;
import com.zonacards.giftcards.GiftCardEstado;
import com.zonacards.giftcards.GiftCardRepository;
import com.zonacards.giftcards.GiftCardRequests.EmitirGiftCardRequest;
import com.zonacards.giftcards.GiftCardRequests.ExtenderVigenciaRequest;
import com.zonacards.giftcards.GiftCardScopes;
import com.zonacards.giftcards.events.GiftcardsEvents;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.Authz;
import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.kernel.Validation;
import com.zonacards.logx.Logx;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Use cases of the giftcards API. Same shape as every service: authorize, validate, then one
 * unit of work that changes the aggregate and writes the event to the outbox.
 */
public final class GiftCardService {

  private static final Logx LOG = Logx.get(GiftCardService.class);
  private static final int INTENTOS_DE_CODIGO = 5;
  private static final int MOTIVO_MAXIMO = 300;

  /** The issued card with its code and CVV: the only moment they are available in the clear. */
  public record Emision(GiftCard card, String codigo, String cvv) {}

  private final GiftCardRepository repository;
  private final GiftCardFactory factory;
  private final UnitOfWork uow;
  private final Outbox outbox;
  private final EventFactory events;
  private final Clock clock;

  public GiftCardService(
      GiftCardRepository repository, GiftCardFactory factory, UnitOfWork uow, Outbox outbox, EventFactory events, Clock clock) {
    this.repository = repository;
    this.factory = factory;
    this.uow = uow;
    this.outbox = outbox;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Issues a single card. The expensive hashing happens before the transaction opens, so no
   * database connection is held while the CPU works.
   */
  public Emision emitir(AuthContext auth, TenantId tenantId, EmitirGiftCardRequest request) {
    Authz.require(auth, tenantId, GiftCardScopes.WRITE);
    Validation.bindAndValidate(request);
    if (!request.vigenciaHasta().isAfter(hoy())) {
      throw Validation.invalid("vigenciaHasta", "must be a future date");
    }
    // A collision of 12 random digits is rare, but at this volume it is a matter of when.
    for (int intento = 1; intento <= INTENTOS_DE_CODIGO; intento++) {
      GiftCardFactory.Fabricada fabricada =
          factory.crear(tenantId, null, null, request.pais(), request.moneda(), request.monto(), request.vigenciaHasta(), clock.instant());
      GiftCard card = fabricada.card();
      boolean stored =
          uow.inTx(
              () -> {
                if (!repository.insert(card)) {
                  return false;
                }
                outbox.publish(
                    GiftcardsEvents.TOPIC,
                    events.create(
                        GiftcardsEvents.GIFTCARD_EMITIDA,
                        tenantId,
                        card.id().value(),
                        new GiftcardsEvents.GiftCardEmitida(card.id().value(), card.pais(), card.moneda(), card.monto(), card.vigenciaHasta())));
                return true;
              });
      if (stored) {
        LOG.with("giftcard_id", card.id()).with("tenant_id", tenantId).with("codigo", card.codigoMascara()).info("gift card emitida");
        return new Emision(card, fabricada.codigo(), fabricada.cvv());
      }
    }
    throw GiftCardErrors.codigoNoDisponible();
  }

  public GiftCard obtener(AuthContext auth, TenantId tenantId, GiftCardId id) {
    Authz.require(auth, tenantId, GiftCardScopes.READ);
    return uow.inTx(() -> repository.get(tenantId, id));
  }

  public Paginated<GiftCard> listar(
      AuthContext auth, TenantId tenantId, LoteId loteId, GiftCardEstado estado, PaginationOptions pagination) {
    Authz.require(auth, tenantId, GiftCardScopes.READ);
    return uow.inTx(() -> repository.list(tenantId, loteId, estado, pagination));
  }

  public GiftCard extenderVigencia(AuthContext auth, TenantId tenantId, GiftCardId id, ExtenderVigenciaRequest request) {
    Authz.require(auth, tenantId, GiftCardScopes.WRITE);
    Validation.bindAndValidate(request);
    return uow.inTx(
        () -> {
          GiftCard card = repository.get(tenantId, id);
          if (card.version() != request.version()) {
            throw GiftCardErrors.versionConflict(id);
          }
          card.extenderVigencia(request.vigenciaHasta(), clock.instant());
          repository.update(card);
          return repository.get(tenantId, id);
        });
  }

  /** Activates a card. Calling it on an active card is harmless and announces nothing. */
  public GiftCard activar(AuthContext auth, TenantId tenantId, GiftCardId id) {
    Authz.require(auth, tenantId, GiftCardScopes.ACTIVAR);
    return uow.inTx(
        () -> {
          GiftCard card = repository.findForUpdate(tenantId, id).orElseThrow(() -> GiftCardErrors.notFound(id));
          if (!card.activar(hoy(), clock.instant())) {
            return card;
          }
          repository.update(card);
          outbox.publish(
              GiftcardsEvents.TOPIC,
              events.create(GiftcardsEvents.GIFTCARD_ACTIVADA, tenantId, id.value(), new GiftcardsEvents.GiftCardActivada(id.value())));
          return repository.get(tenantId, id);
        });
  }

  /** Annuls a card: the "delete" of the API. Nothing is physically removed. */
  public GiftCard anular(AuthContext auth, TenantId tenantId, GiftCardId id, String motivo) {
    Authz.require(auth, tenantId, GiftCardScopes.ANULAR);
    String reason = motivo == null || motivo.isBlank() ? "Anulada por el usuario" : motivo.trim();
    if (reason.length() > MOTIVO_MAXIMO) {
      throw Validation.invalid("motivo", "must be at most " + MOTIVO_MAXIMO + " characters");
    }
    return uow.inTx(
        () -> {
          GiftCard card = repository.findForUpdate(tenantId, id).orElseThrow(() -> GiftCardErrors.notFound(id));
          if (!card.anular(reason, clock.instant())) {
            return card;
          }
          repository.update(card);
          outbox.publish(
              GiftcardsEvents.TOPIC,
              events.create(GiftcardsEvents.GIFTCARD_ANULADA, tenantId, id.value(), new GiftcardsEvents.GiftCardAnulada(id.value(), reason)));
          return repository.get(tenantId, id);
        });
  }

  private LocalDate hoy() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
