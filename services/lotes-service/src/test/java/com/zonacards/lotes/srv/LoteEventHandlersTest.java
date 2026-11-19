package com.zonacards.lotes.srv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.eventx.ConsumeInterceptor;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.MessageDispatcher;
import com.zonacards.eventx.Outcome;
import com.zonacards.eventx.memory.InMemoryInbox;
import com.zonacards.eventx.memory.InMemoryUnitOfWork;
import com.zonacards.eventx.memory.RecordingOutbox;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.lotes.Fixtures;
import com.zonacards.lotes.InMemoryLoteRepository;
import com.zonacards.lotes.Lote;
import com.zonacards.lotes.LoteEstado;
import com.zonacards.lotes.events.GiftcardsEvents;
import com.zonacards.lotes.events.LoteEvents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The saga seen from lotes, under the delivery the broker actually provides: duplicated and out
 * of order. Events go through the real dispatcher (with its deduplication), in many random
 * orders, and the outcome must always be the same.
 */
class LoteEventHandlersTest {

  private static final AuthContext ANA = Fixtures.user(Fixtures.PE, "lotes:*");
  private static final EventCodec CODEC = new EventCodec();
  private static final EventFactory GIFTCARDS = new EventFactory("/zonacards/giftcards", CODEC);

  private InMemoryLoteRepository repository;
  private RecordingOutbox outbox;
  private InMemoryInbox inbox;
  private LoteService service;
  private MessageDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    repository = new InMemoryLoteRepository();
    outbox = new RecordingOutbox();
    inbox = new InMemoryInbox();
    EventFactory events = new EventFactory("/zonacards/lotes", CODEC);
    service = new LoteService(repository, UnitOfWork.direct(), outbox, events, Fixtures.CLOCK);
    LoteEventHandlers handlers = new LoteEventHandlers(repository, outbox, events, Fixtures.CLOCK);
    dispatcher =
        new MessageDispatcher("lotes", handlers.register(new HandlerRegistry()), inbox, new InMemoryUnitOfWork(), CODEC, ConsumeInterceptor.NONE);
  }

  private Lote nuevoLote(int cantidad, String key) {
    return service.crear(ANA, Fixtures.PE, Fixtures.solicitud(cantidad), key).lote();
  }

  private Lote actual(Lote lote) {
    return repository.get(Fixtures.PE, lote.id());
  }

  private static EventEnvelope iniciada(TenantId tenant, LoteId id, int total) {
    return GIFTCARDS.create(GiftcardsEvents.GENERACION_INICIADA, tenant, id.value(), new GiftcardsEvents.GeneracionIniciada(id.value(), total));
  }

  private static EventEnvelope fragmento(LoteId id, int n, int total, int cantidad) {
    return GIFTCARDS.create(
        GiftcardsEvents.FRAGMENTO_GENERADO, Fixtures.PE, id.value(), new GiftcardsEvents.FragmentoGenerado(id.value(), n, total, cantidad));
  }

  private static EventEnvelope fallido(LoteId id, int n) {
    return GIFTCARDS.create(
        GiftcardsEvents.FRAGMENTO_FALLIDO, Fixtures.PE, id.value(), new GiftcardsEvents.FragmentoFallido(id.value(), n, "sin espacio de códigos"));
  }

  private static EventEnvelope anulacionAplicada(LoteId id, int anuladas) {
    return GIFTCARDS.create(
        GiftcardsEvents.LOTE_ANULADO_APLICADO, Fixtures.PE, id.value(), new GiftcardsEvents.LoteAnuladoAplicado(id.value(), anuladas));
  }

  /** The events of a complete generation of {@code total} fragments of 1 000 cards. */
  private static List<EventEnvelope> generacionCompleta(LoteId id, int total) {
    List<EventEnvelope> events = new ArrayList<>();
    events.add(iniciada(Fixtures.PE, id, total));
    for (int n = 1; n <= total; n++) {
      events.add(fragmento(id, n, total, 1000));
    }
    return events;
  }

  @Test
  void inOrderDeliveryCompletesTheLote() {
    Lote lote = nuevoLote(3000, "k");
    for (EventEnvelope event : generacionCompleta(lote.id(), 3)) {
      assertEquals(Outcome.HANDLED, dispatcher.dispatch(event, 1));
    }
    Lote result = actual(lote);
    assertEquals(LoteEstado.GENERADO, result.estado());
    assertEquals(3000, result.generadas());
    assertEquals(3, result.fragmentosCompletados());
    assertEquals(1, outbox.ofType(LoteEvents.GENERADO).size());
    assertEquals(new LoteEvents.LoteGenerado(lote.id().value(), 3000), CODEC.data(outbox.ofType(LoteEvents.GENERADO).get(0), LoteEvents.LoteGenerado.class));
  }

  @Test
  void anyOrderWithDuplicatesGivesTheSameResult() {
    for (int seed = 0; seed < 300; seed++) {
      setUp();
      Lote lote = nuevoLote(6000, "k-" + seed);
      List<EventEnvelope> deliveries = new ArrayList<>(generacionCompleta(lote.id(), 6));
      // Duplicates are the same events again (same ids), which is what a redelivery is.
      deliveries.addAll(new ArrayList<>(deliveries));
      deliveries.addAll(deliveries.subList(0, 3));
      Collections.shuffle(deliveries, new Random(seed));

      for (EventEnvelope event : deliveries) {
        dispatcher.dispatch(event, 1);
      }

      Lote result = actual(lote);
      String context = "seed " + seed;
      assertEquals(LoteEstado.GENERADO, result.estado(), context);
      assertEquals(6000, result.generadas(), context);
      assertEquals(6, result.fragmentosCompletados(), context);
      assertEquals(1, outbox.ofType(LoteEvents.GENERADO).size(), context + ": 'lote generado' must be announced exactly once");
    }
  }

  @Test
  void redeliveryAfterTheInboxWasPurgedStillCountsEachFragmentOnce() {
    Lote lote = nuevoLote(4000, "k");
    List<EventEnvelope> events = generacionCompleta(lote.id(), 4);
    events.forEach(event -> dispatcher.dispatch(event, 1));

    // Deduplication records are purged after their retention. A redrive from the dead-letter
    // queue weeks later must not corrupt the lote: the handlers are idempotent on their own.
    inbox.forgetEverything();
    for (EventEnvelope event : events) {
      assertEquals(Outcome.HANDLED, dispatcher.dispatch(event, 2));
    }

    Lote result = actual(lote);
    assertEquals(LoteEstado.GENERADO, result.estado());
    assertEquals(4000, result.generadas());
    assertEquals(1, outbox.ofType(LoteEvents.GENERADO).size());
  }

  @Test
  void annulmentAtAnyPointWinsAndLateFragmentsAreIgnored() {
    for (int seed = 0; seed < 200; seed++) {
      setUp();
      Lote lote = nuevoLote(5000, "k-" + seed);
      List<EventEnvelope> deliveries = new ArrayList<>(generacionCompleta(lote.id(), 5));
      deliveries.addAll(new ArrayList<>(deliveries));
      Random random = new Random(seed);
      Collections.shuffle(deliveries, random);
      int annulAt = random.nextInt(deliveries.size() + 1);

      for (int i = 0; i < deliveries.size(); i++) {
        if (i == annulAt) {
          service.anular(ANA, Fixtures.PE, lote.id(), "cancelada");
        }
        dispatcher.dispatch(deliveries.get(i), 1);
      }
      if (annulAt == deliveries.size()) {
        service.anular(ANA, Fixtures.PE, lote.id(), "cancelada");
      }
      int generadasAlAnular = actual(lote).generadas();
      dispatcher.dispatch(anulacionAplicada(lote.id(), generadasAlAnular), 1);

      Lote result = actual(lote);
      String context = "seed " + seed + ", annulled before delivery " + annulAt;
      assertEquals(LoteEstado.ANULADO, result.estado(), context);
      assertTrue(result.generadas() <= 5000, context);
      assertNotNull(result.anulacionAplicadaEn(), context);
      assertEquals(1, outbox.ofType(LoteEvents.ANULADO).size(), context);
      assertTrue(outbox.ofType(LoteEvents.GENERADO).size() <= 1, context);
    }
  }

  @Test
  void aFailedFragmentFailsTheLoteOnceAndLaterFragmentsAreIgnored() {
    Lote lote = nuevoLote(3000, "k");
    dispatcher.dispatch(fragmento(lote.id(), 1, 3, 1000), 1);
    dispatcher.dispatch(fallido(lote.id(), 2), 1);
    dispatcher.dispatch(fallido(lote.id(), 3), 1);
    dispatcher.dispatch(fragmento(lote.id(), 3, 3, 1000), 1);

    Lote result = actual(lote);
    assertEquals(LoteEstado.FALLIDO, result.estado());
    assertEquals("Fragmento 2: sin espacio de códigos", result.motivo());
    assertEquals(1000, result.generadas(), "the late fragment was not counted");
    assertEquals(1, outbox.ofType(LoteEvents.FALLIDO).size());
    assertTrue(outbox.ofType(LoteEvents.GENERADO).isEmpty());
  }

  @Test
  void eventsAboutUnknownLotesOrFromAnotherTenantAreAcknowledgedAndChangeNothing() {
    Lote lote = nuevoLote(1000, "k");

    assertEquals(Outcome.HANDLED, dispatcher.dispatch(fragmento(LoteId.newId(), 1, 1, 1000), 1), "unknown lote: acknowledged, not failed");
    // Same lote id, but the envelope says it belongs to another tenant: the handler cannot see it.
    assertEquals(Outcome.HANDLED, dispatcher.dispatch(iniciada(Fixtures.EC, lote.id(), 9), 1));

    Lote result = actual(lote);
    assertEquals(LoteEstado.SOLICITADO, result.estado());
    assertEquals(0, result.fragmentosTotales());
    assertNull(result.anulacionAplicadaEn());
  }

  @Test
  void theConfirmationOfAnAnnulmentIsRecordedOnce() {
    Lote lote = nuevoLote(2000, "k");
    service.anular(ANA, Fixtures.PE, lote.id(), "cancelada");
    dispatcher.dispatch(anulacionAplicada(lote.id(), 1000), 1);
    inbox.forgetEverything();
    dispatcher.dispatch(anulacionAplicada(lote.id(), 0), 1);

    Lote result = actual(lote);
    assertEquals(1000, result.giftcardsAnuladas(), "a later duplicate does not overwrite the first confirmation");
    assertEquals(2, result.version(), "one update for the annulment, one for its confirmation");
  }
}
