package com.zonacards.lotes.srv;

import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.Outbox;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.Authz;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.kernel.Validation;
import com.zonacards.logx.Logx;
import com.zonacards.lotes.Lote;
import com.zonacards.lotes.LoteErrors;
import com.zonacards.lotes.LoteEstado;
import com.zonacards.lotes.LoteRepository;
import com.zonacards.lotes.LoteRequests.ActualizarLoteRequest;
import com.zonacards.lotes.LoteRequests.CrearLoteRequest;
import com.zonacards.lotes.LoteScopes;
import com.zonacards.lotes.events.LoteEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Use cases of the lotes domain. Every method has the same shape, in this order:
 *
 * <ol>
 *   <li>authorize, before anything else touches the database;
 *   <li>validate the request;
 *   <li>one unit of work that changes the aggregate and writes the event to the outbox.
 * </ol>
 *
 * The service never calls the other domain. It states what happened; whoever cares reacts.
 */
public final class LoteService {

  private static final Logx LOG = Logx.get(LoteService.class);
  private static final int MOTIVO_MAXIMO = 300;

  /** @param nuevo false when the request was a replay of an earlier one with the same idempotency key */
  public record Creacion(Lote lote, boolean nuevo) {}

  private final LoteRepository repository;
  private final UnitOfWork uow;
  private final Outbox outbox;
  private final EventFactory events;
  private final Clock clock;

  public LoteService(LoteRepository repository, UnitOfWork uow, Outbox outbox, EventFactory events, Clock clock) {
    this.repository = repository;
    this.uow = uow;
    this.outbox = outbox;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Requests a lote and returns at once; the gift cards are generated asynchronously by the
   * other domain. The idempotency key makes a retried POST safe: the same key with the same body
   * returns the lote created the first time, and no second event is published.
   */
  public Creacion crear(AuthContext auth, TenantId tenantId, CrearLoteRequest request, String idempotencyKey) {
    Authz.require(auth, tenantId, LoteScopes.WRITE);
    Validation.bindAndValidate(request);
    String key = requireKey(idempotencyKey);
    Lote lote = Lote.solicitar(tenantId, request, key, fingerprint(request), auth.actor().id(), clock.instant());

    return uow.inTx(
        () -> {
          if (repository.insert(lote)) {
            outbox.publish(
                LoteEvents.TOPIC,
                events.create(
                    LoteEvents.SOLICITADO,
                    tenantId,
                    lote.id().value(),
                    new LoteEvents.LoteSolicitado(
                        lote.id().value(), lote.pais(), lote.moneda(), lote.monto(), lote.cantidad(), lote.vigenciaHasta())));
            LOG.with("lote_id", lote.id()).with("tenant_id", tenantId).with("cantidad", lote.cantidad()).info("lote solicitado");
            return new Creacion(lote, true);
          }
          Lote existing = repository.findByIdempotencyKey(tenantId, key).orElseThrow(LoteErrors::idempotencyKeyReused);
          if (!existing.requestHash().equals(lote.requestHash())) {
            throw LoteErrors.idempotencyKeyReused();
          }
          return new Creacion(existing, false);
        });
  }

  public Lote obtener(AuthContext auth, TenantId tenantId, LoteId id) {
    Authz.require(auth, tenantId, LoteScopes.READ);
    return uow.inTx(() -> repository.get(tenantId, id));
  }

  public Paginated<Lote> listar(AuthContext auth, TenantId tenantId, LoteEstado estado, PaginationOptions pagination) {
    Authz.require(auth, tenantId, LoteScopes.READ);
    return uow.inTx(() -> repository.list(tenantId, estado, pagination));
  }

  /** Changes descriptive data only. What was requested (amount, quantity, country) is immutable. */
  public Lote actualizar(AuthContext auth, TenantId tenantId, LoteId id, ActualizarLoteRequest request) {
    Authz.require(auth, tenantId, LoteScopes.WRITE);
    Validation.bindAndValidate(request);
    return uow.inTx(
        () -> {
          Lote lote = repository.get(tenantId, id);
          if (lote.version() != request.version()) {
            throw LoteErrors.versionConflict(id);
          }
          lote.actualizarMetadatos(request.descripcion(), request.referenciaExterna(), clock.instant());
          repository.update(lote);
          return repository.get(tenantId, id);
        });
  }

  /**
   * Annuls a lote. This is the "delete" of the API: nothing is physically removed, and the
   * annulment is itself an event, so the other domain annuls the gift cards it generated.
   * Calling it again is harmless.
   */
  public Lote anular(AuthContext auth, TenantId tenantId, LoteId id, String motivo) {
    Authz.require(auth, tenantId, LoteScopes.ANULAR);
    String reason = motivo == null || motivo.isBlank() ? "Anulado por el usuario" : motivo.trim();
    if (reason.length() > MOTIVO_MAXIMO) {
      throw Validation.invalid("motivo", "must be at most " + MOTIVO_MAXIMO + " characters");
    }
    return uow.inTx(
        () -> {
          Lote lote = repository.findForUpdate(tenantId, id).orElseThrow(() -> LoteErrors.notFound(id));
          if (!lote.anular(reason, clock.instant())) {
            return lote;
          }
          repository.update(lote);
          outbox.publish(
              LoteEvents.TOPIC,
              events.create(LoteEvents.ANULADO, tenantId, id.value(), new LoteEvents.LoteAnulado(id.value(), reason)));
          LOG.with("lote_id", id).with("tenant_id", tenantId).info("lote anulado");
          return repository.get(tenantId, id);
        });
  }

  private static String requireKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw LoteErrors.idempotencyKeyRequired();
    }
    String key = idempotencyKey.trim();
    if (key.length() > 100) {
      throw Validation.invalid("Idempotency-Key", "must be at most 100 characters");
    }
    return key;
  }

  /** Identifies the content of a request, to tell a retry from a different request with the same key. */
  private static String fingerprint(CrearLoteRequest request) {
    String canonical =
        String.join(
            "|",
            request.descripcion().trim(),
            String.valueOf(request.referenciaExterna()),
            request.pais(),
            request.moneda(),
            request.monto().setScale(2).toPlainString(),
            String.valueOf(request.cantidad()),
            String.valueOf(request.vigenciaHasta()));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
