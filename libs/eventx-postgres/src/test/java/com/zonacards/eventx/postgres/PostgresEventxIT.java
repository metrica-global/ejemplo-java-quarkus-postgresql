package com.zonacards.eventx.postgres;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.asyncx.Backoff;
import com.zonacards.dbx.Db;
import com.zonacards.dbx.LocalUnitOfWork;
import com.zonacards.eventx.ConsumeInterceptor;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.EventxMetrics;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.MessageDispatcher;
import com.zonacards.eventx.Outbox;
import com.zonacards.eventx.OutboxMessage;
import com.zonacards.eventx.OutboxRelay;
import com.zonacards.eventx.OutboxStats;
import com.zonacards.eventx.QueueConsumer;
import com.zonacards.eventx.TransactionalOutbox;
import com.zonacards.eventx.memory.InMemoryBroker;
import com.zonacards.kernel.TenantId;
import com.zonacards.testkit.PostgresFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresEventxIT {

  private static final String SERVICE = "eventxpg";
  private static final String TYPE = "zonacards.test.created.v1";
  private static final TenantId TENANT = TenantId.of("pe");

  record Payload(int n) {}

  static LocalUnitOfWork uow;
  static Db db;
  static PostgresOutboxStore store;
  static PostgresInbox inbox;
  static final EventCodec CODEC = new EventCodec();
  static final EventFactory FACTORY = new EventFactory("/test", CODEC);
  static Outbox outbox;

  @BeforeAll
  static void start() {
    PostgresFixture postgres = PostgresFixture.shared().bootstrapService(SERVICE, 60);
    postgres.executeAsOwner(SERVICE, "DROP TABLE IF EXISTS outbox, inbox, effect", EventxSchema.ddl(), "CREATE TABLE effect (n int PRIMARY KEY)");
    // The stores run with the runtime role, like the deployed service.
    uow = new LocalUnitOfWork(postgres.appDataSource(SERVICE));
    db = new Db(uow);
    store = new PostgresOutboxStore(db);
    inbox = new PostgresInbox(db);
    outbox = new TransactionalOutbox(store, CODEC);
  }

  @BeforeEach
  void clean() {
    uow.runInTx(
        () -> {
          db.update("DELETE FROM outbox");
          db.update("DELETE FROM inbox");
          db.update("DELETE FROM effect");
        });
  }

  private static EventEnvelope event(int n) {
    return FACTORY.create(TYPE, TENANT, "s-" + n, new Payload(n));
  }

  private static void publish(int count) {
    uow.runInTx(
        () -> {
          for (int i = 0; i < count; i++) {
            outbox.publish("things-events", event(i));
          }
        });
  }

  @Test
  void theOutboxCommitsOrRollsBackWithTheBusinessTransaction() {
    assertThrows(
        IllegalStateException.class,
        () ->
            uow.runInTx(
                () -> {
                  db.update("INSERT INTO effect (n) VALUES (1)");
                  outbox.publish("things-events", event(1));
                  throw new IllegalStateException("business rule failed after the event was written");
                }));
    assertEquals(0, uow.inTx(() -> store.stats()).pending(), "no state change, no event");

    uow.runInTx(
        () -> {
          db.update("INSERT INTO effect (n) VALUES (1)");
          outbox.publish("things-events", event(1));
        });
    assertEquals(1, uow.inTx(() -> store.stats()).pending());
  }

  @Test
  void concurrentRelaysNeverClaimTheSameMessage() throws Exception {
    publish(600);
    int relays = 8;
    Set<UUID> seen = ConcurrentHashMap.newKeySet();
    AtomicInteger duplicates = new AtomicInteger();
    CyclicBarrier startTogether = new CyclicBarrier(relays);
    CountDownLatch done = new CountDownLatch(relays);
    for (int i = 0; i < relays; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  startTogether.await();
                  while (true) {
                    List<OutboxMessage> claimed = uow.inTx(() -> store.claim(25, Duration.ofSeconds(60)));
                    if (claimed.isEmpty()) {
                      break;
                    }
                    for (OutboxMessage message : claimed) {
                      if (!seen.add(message.id())) {
                        duplicates.incrementAndGet();
                      }
                    }
                  }
                } catch (Exception e) {
                  duplicates.addAndGet(1_000_000);
                } finally {
                  done.countDown();
                }
              });
    }
    assertTrue(done.await(60, TimeUnit.SECONDS));
    assertEquals(0, duplicates.get(), "a message was claimed by two relays");
    assertEquals(600, seen.size(), "every message was claimed by exactly one relay");
  }

  @Test
  void anExpiredLeaseMakesTheMessageClaimableAgain() throws Exception {
    publish(1);
    List<OutboxMessage> first = uow.inTx(() -> store.claim(10, Duration.ofMillis(300)));
    assertEquals(1, first.size());
    assertEquals(1, first.get(0).attempts());
    assertEquals(TYPE, first.get(0).type());
    assertEquals("pe", first.get(0).tenantId());
    assertTrue(uow.inTx(() -> store.claim(10, Duration.ofMillis(300))).isEmpty(), "leased: nobody else may take it");

    Thread.sleep(400);
    List<OutboxMessage> second = uow.inTx(() -> store.claim(10, Duration.ofSeconds(30)));
    assertEquals(1, second.size(), "the relay that held it died; the message is free again");
    assertEquals(2, second.get(0).attempts());
  }

  @Test
  void publishedRescheduledAndParkedMessagesFollowTheirLifecycle() throws Exception {
    publish(3);
    List<OutboxMessage> claimed = uow.inTx(() -> store.claim(10, Duration.ofSeconds(30)));
    UUID ok = claimed.get(0).id();
    UUID later = claimed.get(1).id();
    UUID poison = claimed.get(2).id();
    uow.runInTx(
        () -> {
          store.markPublished(List.of(ok));
          store.reschedule(later, "Throttled", Duration.ofMillis(300));
          store.park(poison, "x".repeat(2_000));
        });

    OutboxStats stats = uow.inTx(() -> store.stats());
    assertEquals(1, stats.pending());
    assertEquals(1, stats.parked());
    assertTrue(uow.inTx(() -> store.claim(10, Duration.ofSeconds(30))).isEmpty(), "rescheduled: not yet available");

    Thread.sleep(400);
    List<OutboxMessage> retried = uow.inTx(() -> store.claim(10, Duration.ofSeconds(30)));
    assertEquals(List.of(later), retried.stream().map(OutboxMessage::id).toList(), "only the rescheduled one comes back");
    assertTrue(stats.oldestPendingAge().toMillis() >= 0);

    assertEquals(0, uow.inTx(() -> store.purgePublished(Duration.ofHours(1), 100)), "recent rows are kept for diagnosis");
    assertEquals(1, uow.inTx(() -> store.purgePublished(Duration.ZERO, 100)));
    assertEquals(500, uow.inTx(() -> db.queryOne("SELECT length(last_error) AS l FROM outbox WHERE id = ?", r -> r.integer("l"), poison)).orElseThrow());
  }

  @Test
  void theInboxAdmitsAMessageOnceEvenUnderConcurrency() throws Exception {
    assertTrue(uow.inTx(() -> inbox.firstTime("c", "m-1")));
    assertFalse(uow.inTx(() -> inbox.firstTime("c", "m-1")));
    assertTrue(uow.inTx(() -> inbox.firstTime("other-consumer", "m-1")), "deduplication is per consumer");

    assertThrows(
        IllegalStateException.class,
        () ->
            uow.runInTx(
                () -> {
                  inbox.firstTime("c", "m-2");
                  throw new IllegalStateException("handler failed");
                }));
    assertTrue(uow.inTx(() -> inbox.firstTime("c", "m-2")), "a rolled-back attempt does not count as handled");

    int contenders = 12;
    AtomicInteger winners = new AtomicInteger();
    CyclicBarrier startTogether = new CyclicBarrier(contenders);
    CountDownLatch done = new CountDownLatch(contenders);
    for (int i = 0; i < contenders; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  startTogether.await();
                  if (uow.inTx(() -> inbox.firstTime("c", "m-race"))) {
                    winners.incrementAndGet();
                  }
                } catch (Exception e) {
                  winners.addAndGet(100);
                } finally {
                  done.countDown();
                }
              });
    }
    assertTrue(done.await(30, TimeUnit.SECONDS));
    assertEquals(1, winners.get());
    assertEquals(0, uow.inTx(() -> inbox.purge(Duration.ofHours(1), 100)));
    assertEquals(4, uow.inTx(() -> inbox.purge(Duration.ZERO, 100)));
  }

  @Test
  void twoRelaysAndADuplicatingBrokerStillApplyEveryEventExactlyOnce() {
    InMemoryBroker broker = new InMemoryBroker().topic("things-events").queue("things-inbox", 5).subscribe("things-events", "things-inbox");
    OutboxRelay.Config relayConfig =
        new OutboxRelay.Config(
            20,
            Duration.ofSeconds(30),
            Duration.ofMillis(10),
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(50), 2, 0),
            Duration.ofDays(1),
            Duration.ofDays(1),
            Duration.ofSeconds(1));
    List<OutboxRelay> relays = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      relays.add(new OutboxRelay(store, broker, uow, inbox, EventxMetrics.NONE, relayConfig));
    }
    HandlerRegistry registry =
        new HandlerRegistry().on(TYPE, ctx -> db.update("INSERT INTO effect (n) VALUES (?)", ctx.data(Payload.class).n()));
    MessageDispatcher dispatcher = new MessageDispatcher("things", registry, inbox, uow, CODEC, ConsumeInterceptor.NONE);
    QueueConsumer consumer =
        new QueueConsumer(
            new QueueConsumer.Config(
                "things-inbox", 8, Duration.ofMillis(50), Duration.ofSeconds(10), new Backoff(Duration.ofMillis(10), Duration.ofMillis(50), 2, 0)),
            broker,
            dispatcher,
            EventxMetrics.NONE);

    relays.forEach(OutboxRelay::start);
    consumer.start();
    try {
      List<EventEnvelope> events = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        events.add(event(i));
      }
      uow.runInTx(() -> events.forEach(e -> outbox.publish("things-events", e)));

      await().atMost(Duration.ofSeconds(30)).until(() -> uow.inTx(() -> db.queryLong("SELECT count(*) FROM effect")) == 300);
      assertEquals(0, uow.inTx(() -> store.stats()).pending());
      assertEquals(300, broker.delivered("things-inbox"), "two relays, yet every event was published exactly once");

      // Now the broker redelivers 40 events that were already handled. The handler's INSERT would
      // violate the primary key if it ran again; the inbox makes sure it does not.
      for (int i = 0; i < 40; i++) {
        broker.inject("things-inbox", CODEC.encode(events.get(i)), java.util.Map.of("type", TYPE));
      }
      await().atMost(Duration.ofSeconds(30)).until(() -> broker.depth("things-inbox") == 0);
      assertEquals(300, uow.inTx(() -> db.queryLong("SELECT count(*) FROM effect")));
      assertEquals(300, uow.inTx(() -> db.queryLong("SELECT count(*) FROM inbox WHERE consumer = 'things'")));
      assertEquals(0, broker.depth("things-inbox-dlq"), "duplicates are acknowledged, not failed");
    } finally {
      consumer.stop(Duration.ofSeconds(10));
      relays.forEach(r -> r.stop(Duration.ofSeconds(10)));
    }
    assertEquals(0, broker.depth("things-inbox"));
  }
}
