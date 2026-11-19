package com.zonacards.lotes.srv;

import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.MessageContext;
import com.zonacards.eventx.Outbox;
import com.zonacards.kernel.LoteId;
import com.zonacards.logx.Logx;
import com.zonacards.lotes.Lote;
import com.zonacards.lotes.LoteRepository;
import com.zonacards.lotes.events.GiftcardsEvents;
import com.zonacards.lotes.events.LoteEvents;
import java.time.Clock;
import java.util.Optional;

/**
 * How lotes reacts to what happens in giftcards. Each handler runs in one transaction together
 * with the deduplication record, and is written for at-least-once, unordered delivery:
 *
 * <ul>
 *   <li>the lote row is locked first, so two events of the same lote are applied one at a time;
 *   <li>progress is a set of fragment numbers, not a counter: a fragment counts once however
 *       many times its event arrives, even after the deduplication records were purged;
 *   <li>an event about a lote that is already terminal is acknowledged and ignored, never failed:
 *       failing would only send a harmless latecomer to the dead-letter queue.
 * </ul>
 */
public final class LoteEventHandlers {

  private static final Logx LOG = Logx.get(LoteEventHandlers.class);

  private final LoteRepository repository;
  private final Outbox outbox;
  private final EventFactory events;
  private final Clock clock;

  public LoteEventHandlers(LoteRepository repository, Outbox outbox, EventFactory events, Clock clock) {
    this.repository = repository;
    this.outbox = outbox;
    this.events = events;
    this.clock = clock;
  }

  /** The complete list of what this service consumes. */
  public HandlerRegistry register(HandlerRegistry registry) {
    return registry
        .on(GiftcardsEvents.GENERACION_INICIADA, this::onGeneracionIniciada)
        .on(GiftcardsEvents.FRAGMENTO_GENERADO, this::onFragmentoGenerado)
        .on(GiftcardsEvents.FRAGMENTO_FALLIDO, this::onFragmentoFallido)
        .on(GiftcardsEvents.LOTE_ANULADO_APLICADO, this::onAnulacionAplicada);
  }

  void onGeneracionIniciada(MessageContext context) {
    GiftcardsEvents.GeneracionIniciada event = context.data(GiftcardsEvents.GeneracionIniciada.class);
    lock(context, event.loteId())
        .ifPresent(
            lote -> {
              if (lote.iniciarGeneracion(event.totalFragmentos(), clock.instant())) {
                repository.update(lote);
              }
            });
  }

  void onFragmentoGenerado(MessageContext context) {
    GiftcardsEvents.FragmentoGenerado event = context.data(GiftcardsEvents.FragmentoGenerado.class);
    Optional<Lote> found = lock(context, event.loteId());
    if (found.isEmpty()) {
      return;
    }
    Lote lote = found.get();
    if (lote.estado().terminal()) {
      LOG.with("lote_id", lote.id()).with("estado", lote.estado()).with("fragmento", event.fragmento()).info("evento tardío ignorado");
      return;
    }
    if (!repository.addFragmento(lote.id(), event.fragmento(), event.cantidad())) {
      return;
    }
    Lote.Progreso progreso = lote.registrarFragmento(event.totalFragmentos(), event.cantidad(), clock.instant());
    repository.update(lote);
    if (progreso == Lote.Progreso.COMPLETADO) {
      outbox.publish(
          LoteEvents.TOPIC,
          events.create(
              LoteEvents.GENERADO,
              lote.tenantId(),
              lote.id().value(),
              new LoteEvents.LoteGenerado(lote.id().value(), lote.generadas())));
      LOG.with("lote_id", lote.id()).with("generadas", lote.generadas()).info("lote generado");
    }
  }

  void onFragmentoFallido(MessageContext context) {
    GiftcardsEvents.FragmentoFallido event = context.data(GiftcardsEvents.FragmentoFallido.class);
    lock(context, event.loteId())
        .ifPresent(
            lote -> {
              String motivo = "Fragmento " + event.fragmento() + ": " + event.motivo();
              if (lote.fallar(motivo, clock.instant())) {
                repository.update(lote);
                outbox.publish(
                    LoteEvents.TOPIC,
                    events.create(
                        LoteEvents.FALLIDO, lote.tenantId(), lote.id().value(), new LoteEvents.LoteFallido(lote.id().value(), motivo)));
                LOG.with("lote_id", lote.id()).with("motivo", motivo).warn("lote fallido");
              }
            });
  }

  void onAnulacionAplicada(MessageContext context) {
    GiftcardsEvents.LoteAnuladoAplicado event = context.data(GiftcardsEvents.LoteAnuladoAplicado.class);
    lock(context, event.loteId())
        .ifPresent(
            lote -> {
              if (lote.anulacionAplicadaEn() == null) {
                lote.confirmarAnulacion(event.giftcardsAnuladas(), clock.instant());
                repository.update(lote);
              }
            });
  }

  /** The tenant comes from the event envelope: a handler can only touch rows of that tenant. */
  private Optional<Lote> lock(MessageContext context, String loteId) {
    Optional<Lote> lote = repository.findForUpdate(context.tenantId(), LoteId.of(loteId));
    if (lote.isEmpty()) {
      LOG.with("lote_id", loteId).with("type", context.type()).warn("evento sobre un lote desconocido; se descarta");
    }
    return lote;
  }
}
