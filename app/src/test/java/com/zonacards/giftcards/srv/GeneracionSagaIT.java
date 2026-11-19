package com.zonacards.giftcards.srv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.asyncx.ComputePool;
import com.zonacards.dbx.Db;
import com.zonacards.dbx.LocalUnitOfWork;
import com.zonacards.eventx.ConsumeInterceptor;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.MessageDispatcher;
import com.zonacards.eventx.Outbox;
import com.zonacards.eventx.Outcome;
import com.zonacards.eventx.TransactionalOutbox;
import com.zonacards.eventx.postgres.EventxSchema;
import com.zonacards.eventx.postgres.PostgresInbox;
import com.zonacards.eventx.postgres.PostgresOutboxStore;
import com.zonacards.giftcards.CodigoGenerator;
import com.zonacards.giftcards.CodigoProtector;
import com.zonacards.giftcards.Fixtures;
import com.zonacards.giftcards.GiftCard;
import com.zonacards.giftcards.Plan;
import com.zonacards.giftcards.events.GeneracionCommands;
import com.zonacards.giftcards.events.GiftcardsEvents;
import com.zonacards.giftcards.events.LotesEvents;
import com.zonacards.giftcards.infra.PostgresGiftCardRepository;
import com.zonacards.giftcards.infra.PostgresPlanRepository;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.testkit.ContractExamples;
import com.zonacards.testkit.PostgresFixture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The generation saga against a real PostgreSQL 18, with the runtime role and the real
 * migrations. No broker and no framework: messages are handed straight to the dispatcher, which
 * lets the test deliver them duplicated, out of order and from several threads at once. What is
 * being proven is the SQL: the locks and conditional writes that keep the result correct.
 */
class GeneracionSagaIT {

  private static final String SERVICE = "gcsaga";
  private static final int FRAGMENTO = 50;
  private static final EventCodec CODEC = new EventCodec();
  private static final EventFactory LOTES = new EventFactory("/zonacards/lotes", CODEC);

  static LocalUnitOfWork uow;
  static Db db;
  static PostgresPlanRepository planes;
  static PostgresGiftCardRepository giftcards;
  static PostgresInbox inbox;
  static Outbox outbox;
  static ComputePool cpu;
  static final CodigoProtector PROTECTOR = Fixtures.protector();

  @BeforeAll
  static void start() throws IOException {
    PostgresFixture postgres = PostgresFixture.shared().bootstrapService(SERVICE, 80);
    String migration;
    try (InputStream in = GeneracionSagaIT.class.getResourceAsStream("/db/giftcards/V1__giftcard.sql")) {
      migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    postgres.executeAsOwner(
        SERVICE, "DROP TABLE IF EXISTS giftcard, plan_fragmento, plan_generacion, outbox, inbox CASCADE", migration, EventxSchema.ddl());
    uow = new LocalUnitOfWork(postgres.appDataSource(SERVICE));
    db = new Db(uow);
    planes = new PostgresPlanRepository(db);
    giftcards = new PostgresGiftCardRepository(db);
    inbox = new PostgresInbox(db);
    outbox = new TransactionalOutbox(new PostgresOutboxStore(db), CODEC);
    cpu = new ComputePool("test-cpu", 4);
  }

  @AfterAll
  static void stop() {
    cpu.close();
  }

  @BeforeEach
  void clean() {
    uow.runInTx(
        () -> {
          db.update("DELETE FROM giftcard");
          db.update("DELETE FROM plan_generacion");
          db.update("DELETE FROM outbox");
          db.update("DELETE FROM inbox");
        });
  }

  // --- harness ---------------------------------------------------------------------------------

  private static MessageDispatcher dispatcher() {
    return dispatcher(PROTECTOR, outbox);
  }

  private static MessageDispatcher dispatcher(CodigoProtector protector, Outbox out) {
    GiftCardFactory factory = new GiftCardFactory(protector, Fixtures.fastHasher(), new SecureRandom());
    GeneracionHandlers handlers =
        new GeneracionHandlers(
            planes, giftcards, factory, cpu, uow, out, new EventFactory("/zonacards/giftcards", CODEC), Clock.systemUTC(), FRAGMENTO);
    return new MessageDispatcher("giftcards", handlers.register(new HandlerRegistry()), inbox, uow, CODEC, ConsumeInterceptor.NONE);
  }

  /** The request event, built from the contract example the producer is tested against. */
  private static EventEnvelope solicitado(LoteId lote, String tenant, int cantidad) {
    return CODEC.decode(
        ContractExamples.instance("zonacards.lotes.lote-solicitado.v1", tenant, Map.of("loteId", lote.value(), "cantidad", cantidad)));
  }

  private static EventEnvelope anulado(LoteId lote) {
    return CODEC.decode(ContractExamples.instance("zonacards.lotes.lote-anulado.v1", "pe", Map.of("loteId", lote.value())));
  }

  private static List<EventEnvelope> outboxOf(String type, LoteId lote) {
    return uow.inTx(
        () ->
            db.queryList(
                "SELECT payload FROM outbox WHERE message_type = ? AND payload LIKE ? ORDER BY created_at, id",
                row -> CODEC.decode(row.string("payload")),
                type,
                "%" + lote.value() + "%"));
  }

  private static List<EventEnvelope> comandos(LoteId lote) {
    return outboxOf(GeneracionCommands.GENERAR_FRAGMENTO, lote);
  }

  private static long contar(String sql, Object... params) {
    return uow.inTx(() -> db.queryLong(sql, params));
  }

  private static long tarjetas(LoteId lote) {
    return contar("SELECT count(*) FROM giftcard WHERE lote_id = ?", lote);
  }

  private static Plan.Estado estadoDelPlan(LoteId lote) {
    return uow.inTx(() -> planes.find(lote)).orElseThrow().estado();
  }

  /** Delivers the messages from several threads at once and waits for all of them. */
  private static void entregarEnParalelo(MessageDispatcher dispatcher, Collection<EventEnvelope> messages, int threads) throws Exception {
    ConcurrentLinkedQueue<EventEnvelope> queue = new ConcurrentLinkedQueue<>(messages);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    CountDownLatch done = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  for (EventEnvelope message = queue.poll(); message != null; message = queue.poll()) {
                    dispatcher.dispatch(message, 1);
                  }
                } catch (Throwable t) {
                  failures.add(t);
                } finally {
                  done.countDown();
                }
              });
    }
    assertTrue(done.await(120, TimeUnit.SECONDS), "deliveries did not finish");
    assertTrue(failures.isEmpty(), "a delivery failed: " + failures);
  }

  private static List<EventEnvelope> duplicadasYBarajadas(List<EventEnvelope> messages, long seed) {
    List<EventEnvelope> deliveries = new ArrayList<>(messages);
    deliveries.addAll(messages);
    Collections.shuffle(deliveries, new Random(seed));
    return deliveries;
  }

  // --- planning --------------------------------------------------------------------------------

  @Test
  void aRequestIsPlannedOnceAndSplitIntoFragmentsThatCoverTheLote() {
    LoteId lote = LoteId.newId();
    EventEnvelope request = solicitado(lote, "pe", 520);

    assertEquals(Outcome.HANDLED, dispatcher().dispatch(request, 1));
    assertEquals(Outcome.DUPLICATE, dispatcher().dispatch(request, 2), "same event again: stopped by the inbox");
    assertEquals(Outcome.HANDLED, dispatcher().dispatch(solicitado(lote, "pe", 520), 1), "a different event for the same lote: stopped by the plan");

    Plan plan = uow.inTx(() -> planes.find(lote)).orElseThrow();
    assertEquals(Plan.Estado.EN_CURSO, plan.estado());
    assertEquals(11, plan.totalFragmentos());
    List<GeneracionCommands.GenerarFragmento> commands =
        comandos(lote).stream().map(e -> CODEC.data(e, GeneracionCommands.GenerarFragmento.class)).toList();
    assertEquals(11, commands.size(), "one command per fragment, written once");
    int expectedFrom = 1;
    List<GeneracionCommands.GenerarFragmento> ordered =
        commands.stream().sorted(java.util.Comparator.comparingInt(GeneracionCommands.GenerarFragmento::fragmento)).toList();
    for (int i = 0; i < ordered.size(); i++) {
      GeneracionCommands.GenerarFragmento command = ordered.get(i);
      assertEquals(i + 1, command.fragmento());
      assertEquals(expectedFrom, command.desdeSecuencia());
      assertEquals(i < 10 ? 50 : 20, command.cantidad());
      assertEquals(11, command.totalFragmentos());
      expectedFrom += command.cantidad();
    }
    assertEquals(521, expectedFrom, "the fragments cover 1..520 with no gap and no overlap");
    assertEquals(11, contar("SELECT count(*) FROM outbox WHERE destination_kind = 'QUEUE' AND destination = 'giftcards-generacion'"));
    assertEquals(1, outboxOf(GiftcardsEvents.GENERACION_INICIADA, lote).size());
    assertEquals(11, contar("SELECT count(*) FROM plan_fragmento WHERE lote_id = ? AND estado = 'PENDIENTE'", lote));
  }

  // --- generation ------------------------------------------------------------------------------

  @Test
  void duplicatedUnorderedConcurrentDeliveryCreatesExactlyTheRequestedCards() throws Exception {
    LoteId lote = LoteId.newId();
    dispatcher().dispatch(solicitado(lote, "pe", 520), 1);

    entregarEnParalelo(dispatcher(), duplicadasYBarajadas(comandos(lote), 11), 8);

    assertEquals(520, tarjetas(lote));
    assertEquals(520, contar("SELECT count(DISTINCT secuencia) FROM giftcard WHERE lote_id = ? AND secuencia BETWEEN 1 AND 520", lote));
    assertEquals(520, contar("SELECT count(*) FROM giftcard WHERE lote_id = ? AND estado = 'GENERADA' AND tenant_id = 'pe'", lote));
    assertEquals(11, contar("SELECT count(*) FROM plan_fragmento WHERE lote_id = ? AND estado = 'GENERADO'", lote));

    List<GiftcardsEvents.FragmentoGenerado> events =
        outboxOf(GiftcardsEvents.FRAGMENTO_GENERADO, lote).stream().map(e -> CODEC.data(e, GiftcardsEvents.FragmentoGenerado.class)).toList();
    assertEquals(11, events.size(), "each fragment is announced once, however many times its command arrived");
    assertEquals(11, events.stream().map(GiftcardsEvents.FragmentoGenerado::fragmento).distinct().count());
    assertEquals(520, events.stream().mapToInt(GiftcardsEvents.FragmentoGenerado::cantidad).sum());

    // The secrets: every code is well formed and unique; nothing is stored in the clear.
    List<GiftCard> cards = uow.inTx(() -> giftcards.list(Fixtures.PE, lote, null, new PaginationOptions(1, 1000))).items();
    Set<String> codes = new HashSet<>();
    for (GiftCard card : cards) {
      String code = PROTECTOR.revelar(card.codigoCifrado());
      assertTrue(CodigoGenerator.esValido(code) && code.startsWith("604"), code);
      assertEquals(CodigoGenerator.mascara(code), card.codigoMascara());
      assertTrue(card.cvvHash().startsWith("$argon2id$"));
      codes.add(code);
    }
    assertEquals(520, codes.size());
    assertEquals(1, cards.get(0).secuencia());
    assertEquals(520, cards.get(519).secuencia());
  }

  @Test
  void redeliveryLongAfterwardsWithThePurgedInboxChangesNothing() throws Exception {
    LoteId lote = LoteId.newId();
    EventEnvelope request = solicitado(lote, "pe", 200);
    dispatcher().dispatch(request, 1);
    List<EventEnvelope> commands = comandos(lote);
    entregarEnParalelo(dispatcher(), commands, 4);
    long outboxRows = contar("SELECT count(*) FROM outbox");

    // A redrive from the dead-letter queue weeks later: the deduplication records are gone.
    uow.runInTx(() -> db.update("DELETE FROM inbox"));
    dispatcher().dispatch(request, 3);
    entregarEnParalelo(dispatcher(), duplicadasYBarajadas(commands, 3), 4);

    assertEquals(200, tarjetas(lote));
    assertEquals(outboxRows, contar("SELECT count(*) FROM outbox"), "nothing was announced twice");
  }

  @Test
  void aFailureBeforeTheCommitLeavesNothingAndTheRetryCompletesTheFragment() {
    LoteId lote = LoteId.newId();
    dispatcher().dispatch(solicitado(lote, "pe", 100), 1);
    EventEnvelope command = comandos(lote).get(0);

    AtomicInteger publishes = new AtomicInteger();
    Outbox failingOnce =
        new Outbox() {
          @Override
          public void publish(String topic, EventEnvelope event) {
            if (publishes.incrementAndGet() == 1) {
              throw new IllegalStateException("the task dies right before committing");
            }
            outbox.publish(topic, event);
          }

          @Override
          public void send(String queue, EventEnvelope cmd) {
            outbox.send(queue, cmd);
          }

          @Override
          public void sendAll(String queue, Collection<EventEnvelope> commands) {
            outbox.sendAll(queue, commands);
          }
        };
    MessageDispatcher crashing = dispatcher(PROTECTOR, failingOnce);

    assertThrows(IllegalStateException.class, () -> crashing.dispatch(command, 1));
    assertEquals(0, tarjetas(lote), "the cards were inserted in the transaction that rolled back");
    assertEquals(2, contar("SELECT count(*) FROM plan_fragmento WHERE lote_id = ? AND estado = 'PENDIENTE'", lote));
    assertEquals(0, contar("SELECT count(*) FROM inbox WHERE message_id = ?", command.id()), "not recorded as handled");

    assertEquals(Outcome.HANDLED, crashing.dispatch(command, 2));
    assertEquals(50, tarjetas(lote));
    assertEquals(1, outboxOf(GiftcardsEvents.FRAGMENTO_GENERADO, lote).size());
  }

  // --- code collisions -------------------------------------------------------------------------

  /** A protector that gives the blind index of an existing card to the codes chosen by {@code collides}. */
  private static CodigoProtector colliding(byte[] existingIndex, UnaryOperator<Integer> remainingCollisions) {
    AtomicInteger calls = new AtomicInteger();
    return new CodigoProtector() {
      @Override
      public Protegido proteger(String codigo) {
        Protegido real = PROTECTOR.proteger(codigo);
        return remainingCollisions.apply(calls.incrementAndGet()) > 0 ? new Protegido(real.cifrado(), existingIndex, real.mascara()) : real;
      }

      @Override
      public String revelar(byte[] cifrado) {
        return PROTECTOR.revelar(cifrado);
      }
    };
  }

  private static byte[] indexOfAnExistingCard() {
    GiftCard existing =
        new GiftCardFactory(PROTECTOR, Fixtures.fastHasher(), new SecureRandom())
            .crear(Fixtures.PE, null, null, "604", "PEN", java.math.BigDecimal.TEN, java.time.LocalDate.parse("2027-12-31"), Fixtures.NOW)
            .card();
    assertTrue(uow.inTx(() -> giftcards.insert(existing)));
    assertFalse(uow.inTx(() -> giftcards.insert(existing)), "the database refuses a second card with the same code");
    return existing.codigoIndice();
  }

  @Test
  void collidingCodesAreReplacedInsideTheSameTransaction() {
    byte[] taken = indexOfAnExistingCard();
    LoteId lote = LoteId.newId();
    dispatcher().dispatch(solicitado(lote, "pe", 50), 1);

    // Seven of the fifty codes collide with a card that already exists.
    MessageDispatcher unlucky = dispatcher(colliding(taken, call -> call <= 7 ? 1 : 0), outbox);
    assertEquals(Outcome.HANDLED, unlucky.dispatch(comandos(lote).get(0), 1));

    assertEquals(50, tarjetas(lote), "the fragment is complete: the seven were given new codes");
    assertEquals(50, contar("SELECT count(DISTINCT secuencia) FROM giftcard WHERE lote_id = ?", lote));
    assertEquals(51, contar("SELECT count(DISTINCT codigo_indice) FROM giftcard"));
    assertEquals(1, outboxOf(GiftcardsEvents.FRAGMENTO_GENERADO, lote).size());
  }

  @Test
  void whenNoUniqueCodeCanBeFoundTheFragmentFailsAndTheLoteIsAbandoned() {
    byte[] taken = indexOfAnExistingCard();
    LoteId lote = LoteId.newId();
    dispatcher().dispatch(solicitado(lote, "pe", 150), 1);
    List<EventEnvelope> commands = comandos(lote);

    MessageDispatcher doomed = dispatcher(colliding(taken, call -> 1), outbox);
    assertEquals(Outcome.HANDLED, doomed.dispatch(commands.get(1), 1), "a terminal failure is an outcome, not a retry");

    assertEquals(0, tarjetas(lote), "the partial inserts were rolled back");
    assertEquals(Plan.Estado.FALLIDO, estadoDelPlan(lote));
    List<EventEnvelope> failed = outboxOf(GiftcardsEvents.FRAGMENTO_FALLIDO, lote);
    assertEquals(1, failed.size());
    ContractExamples.assertMatches(GiftcardsEvents.FRAGMENTO_FALLIDO, CODEC.toData(failed.get(0)));
    assertEquals(2, CODEC.data(failed.get(0), GiftcardsEvents.FragmentoFallido.class).fragmento());

    // The other fragments are not generated any more, and a redelivery of the failed one is harmless.
    dispatcher().dispatch(commands.get(0), 1);
    dispatcher().dispatch(commands.get(2), 1);
    uow.runInTx(() -> db.update("DELETE FROM inbox"));
    doomed.dispatch(commands.get(1), 2);
    assertEquals(0, tarjetas(lote));
    assertEquals(1, outboxOf(GiftcardsEvents.FRAGMENTO_FALLIDO, lote).size());

    // Annulling the failed lote closes it.
    dispatcher().dispatch(anulado(lote), 1);
    assertEquals(Plan.Estado.ANULADO, estadoDelPlan(lote));
  }

  // --- annulment -------------------------------------------------------------------------------

  @Test
  void anAnnulmentThatArrivesBeforeTheRequestLeavesATombstone() throws Exception {
    LoteId lote = LoteId.newId();

    assertEquals(Outcome.HANDLED, dispatcher().dispatch(anulado(lote), 1));
    assertEquals(Plan.Estado.ANULADO, estadoDelPlan(lote));
    dispatcher().dispatch(solicitado(lote, "pe", 300), 1);

    assertTrue(comandos(lote).isEmpty(), "no work is handed out for a lote that is already annulled");
    assertTrue(outboxOf(GiftcardsEvents.GENERACION_INICIADA, lote).isEmpty());
    assertEquals(0, tarjetas(lote));
    List<EventEnvelope> applied = outboxOf(GiftcardsEvents.LOTE_ANULADO_APLICADO, lote);
    assertEquals(1, applied.size());
    assertEquals(0, CODEC.data(applied.get(0), GiftcardsEvents.LoteAnuladoAplicado.class).giftcardsAnuladas());
    ContractExamples.assertMatches(GiftcardsEvents.LOTE_ANULADO_APLICADO, CODEC.toData(applied.get(0)));
  }

  @Test
  void anAnnulmentAfterTheGenerationAnnulsEveryCard() throws Exception {
    LoteId lote = LoteId.newId();
    dispatcher().dispatch(solicitado(lote, "pe", 230), 1);
    entregarEnParalelo(dispatcher(), comandos(lote), 4);
    assertEquals(230, tarjetas(lote));

    EventEnvelope annulment = anulado(lote);
    dispatcher().dispatch(annulment, 1);
    uow.runInTx(() -> db.update("DELETE FROM inbox"));
    dispatcher().dispatch(annulment, 2);

    assertEquals(230, contar("SELECT count(*) FROM giftcard WHERE lote_id = ? AND estado = 'ANULADA' AND anulada_en IS NOT NULL", lote));
    List<EventEnvelope> applied = outboxOf(GiftcardsEvents.LOTE_ANULADO_APLICADO, lote);
    assertEquals(1, applied.size(), "applied once, announced once");
    assertEquals(230, CODEC.data(applied.get(0), GiftcardsEvents.LoteAnuladoAplicado.class).giftcardsAnuladas());
  }

  @Test
  void anAnnulmentRacingWithTheGenerationNeverLeavesALiveCard() throws Exception {
    Random random = new Random(2026);
    int interrupted = 0;
    for (int round = 0; round < 8; round++) {
      LoteId lote = LoteId.newId();
      dispatcher().dispatch(solicitado(lote, "pe", 600), 1);
      List<EventEnvelope> commands = duplicadasYBarajadas(comandos(lote), round);
      // The annulment is fired while fragments are being stored: as soon as this many are done.
      int afterFragments = 1 + random.nextInt(8);

      Thread annulment =
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (contar("SELECT count(*) FROM plan_fragmento WHERE lote_id = ? AND estado = 'GENERADO'", lote) < afterFragments) {
                      Thread.onSpinWait();
                    }
                    dispatcher().dispatch(anulado(lote), 1);
                  });
      entregarEnParalelo(dispatcher(), commands, 6);
      annulment.join();

      String context = "round " + round + ", annulled after " + afterFragments + " fragments";
      long total = tarjetas(lote);
      long generados = contar("SELECT count(*) FROM plan_fragmento WHERE lote_id = ? AND estado = 'GENERADO'", lote);
      assertEquals(Plan.Estado.ANULADO, estadoDelPlan(lote), context);
      assertEquals(0, contar("SELECT count(*) FROM giftcard WHERE lote_id = ? AND estado <> 'ANULADA'", lote), context + ": a live card in an annulled lote");
      assertEquals(generados * FRAGMENTO, total, context + ": every stored fragment is complete");
      List<EventEnvelope> applied = outboxOf(GiftcardsEvents.LOTE_ANULADO_APLICADO, lote);
      assertEquals(1, applied.size(), context);
      assertEquals(
          total,
          CODEC.data(applied.get(0), GiftcardsEvents.LoteAnuladoAplicado.class).giftcardsAnuladas(),
          context + ": the annulment saw every card that exists; none was added after it");
      assertEquals(generados, outboxOf(GiftcardsEvents.FRAGMENTO_GENERADO, lote).size(), context);
      if (generados < 12) {
        interrupted++;
      }
    }
    assertTrue(interrupted >= 4, "the annulment should cut the generation short in most rounds, but did in only " + interrupted);
  }

  @Test
  void messagesAboutAnotherTenantOrAnUnknownPlanAreDiscarded() {
    LoteId lote = LoteId.newId();
    dispatcher().dispatch(solicitado(lote, "pe", 50), 1);
    EventEnvelope command = comandos(lote).get(0);

    // The same command, but its envelope claims another tenant.
    EventEnvelope forged =
        new EventEnvelope(
            command.specversion(), LOTES.create("x", Fixtures.EC, "x", Map.of()).id(), command.source(), command.type(), command.subject(),
            command.time(), command.datacontenttype(), "ec", command.correlationid(), null, command.data());
    assertEquals(Outcome.HANDLED, dispatcher().dispatch(forged, 1));
    assertEquals(0, tarjetas(lote));

    // An annulment from another tenant does not touch this tenant's plan.
    EventEnvelope foreignAnnulment =
        CODEC.decode(ContractExamples.instance("zonacards.lotes.lote-anulado.v1", "ec", Map.of("loteId", lote.value())));
    dispatcher().dispatch(foreignAnnulment, 1);
    assertEquals(Plan.Estado.EN_CURSO, estadoDelPlan(lote));
    assertTrue(outboxOf(GiftcardsEvents.LOTE_ANULADO_APLICADO, lote).isEmpty());

    dispatcher().dispatch(command, 1);
    assertEquals(50, tarjetas(lote));
  }
}
