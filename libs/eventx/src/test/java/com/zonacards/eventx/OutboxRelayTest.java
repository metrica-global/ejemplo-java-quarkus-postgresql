package com.zonacards.eventx;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.eventx.memory.InMemoryBroker;
import com.zonacards.eventx.memory.InMemoryOutboxStore;
import com.zonacards.kernel.UnitOfWork;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

  private static final String TOPIC = "things-events";
  private static final String CREATED = "zonacards.test.created.v1";
  private static final String DELETED = "zonacards.test.deleted.v1";

  private InMemoryBroker broker;
  private InMemoryOutboxStore store;
  private Outbox outbox;

  @BeforeEach
  void setUp() {
    broker =
        new InMemoryBroker()
            .topic(TOPIC)
            .queue("all", 5)
            .queue("only-created", 5)
            .queue("work", 5)
            .subscribe(TOPIC, "all")
            .subscribe(TOPIC, "only-created", CREATED);
    store = new InMemoryOutboxStore();
    outbox = new TransactionalOutbox(store, Fixtures.CODEC);
  }

  private OutboxRelay relay(OutboxStore outboxStore) {
    return new OutboxRelay(outboxStore, broker, UnitOfWork.direct(), null, EventxMetrics.NONE, Fixtures.relayConfig());
  }

  @Test
  void eventsFanOutThroughTheTopicAndCommandsGoStraightToTheirQueue() {
    outbox.publish(TOPIC, Fixtures.event(CREATED, 1));
    outbox.publish(TOPIC, Fixtures.event(DELETED, 2));
    outbox.send("work", Fixtures.event("zonacards.test.do-work.v1", 3));

    assertEquals(3, relay(store).runOnce());

    assertEquals(2, broker.depth("all"));
    assertEquals(1, broker.depth("only-created"), "the subscription filter drops what the consumer did not ask for");
    assertEquals(1, broker.depth("work"));
    assertEquals(3, store.publishedCount());
    assertEquals(0, relay(store).runOnce(), "published messages are not sent again");
  }

  @Test
  void aBatchNeverExceedsTheBrokerLimit() {
    List<EventEnvelope> commands = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      commands.add(Fixtures.event("zonacards.test.do-work.v1", i));
    }
    outbox.sendAll("work", commands);

    relay(store).runOnce();

    assertEquals(25, broker.depth("work"));
    assertEquals(3, broker.sendCalls(), "25 messages are 3 batches of at most 10");
  }

  @Test
  void aPartiallyAcceptedBatchOnlyRetriesWhatFailed() {
    for (int i = 0; i < 6; i++) {
      outbox.send("work", Fixtures.event("zonacards.test.do-work.v1", i));
    }
    broker.failTransientlyWhen(m -> m.payload().contains("\"n\":2") || m.payload().contains("\"n\":4"));

    OutboxRelay relay = relay(store);
    relay.runOnce();
    assertEquals(4, broker.depth("work"));
    assertEquals(2, store.stats().pending());

    broker.failTransientlyWhen(m -> false);
    await().atMost(Duration.ofSeconds(5)).until(() -> relay.runOnce() >= 0 && store.stats().pending() == 0);
    assertEquals(6, broker.depth("work"), "each message was delivered exactly once");
  }

  @Test
  void aMessageTheBrokerRejectsIsParkedInsteadOfBlockingTheRest() {
    EventEnvelope poison = Fixtures.event(CREATED, 13);
    outbox.publish(TOPIC, poison);
    outbox.publish(TOPIC, Fixtures.event(CREATED, 14));
    broker.rejectWhen(m -> m.id().toString().equals(poison.id()));

    OutboxRelay relay = relay(store);
    relay.runOnce();

    OutboxStats stats = store.stats();
    assertEquals(1, stats.parked());
    assertEquals(0, stats.pending());
    assertEquals(1, broker.depth("all"));
    assertEquals("InvalidParameter", store.lastError(UUID.fromString(poison.id())));
    assertEquals(0, relay.runOnce(), "a parked message is not retried");
  }

  @Test
  void aBrokerOutageLosesNothing() {
    for (int i = 0; i < 12; i++) {
      outbox.publish(TOPIC, Fixtures.event(CREATED, i));
    }
    broker.setDown(true);
    OutboxRelay relay = relay(store);
    relay.runOnce();
    assertEquals(0, broker.depth("all"));
    assertEquals(12, store.stats().pending());
    assertTrue(store.stats().parked() == 0, "an outage is transient: nothing is parked");

    broker.setDown(false);
    await().atMost(Duration.ofSeconds(5)).until(() -> relay.runOnce() >= 0 && store.stats().pending() == 0);
    assertEquals(12, broker.depth("all"));
  }

  @Test
  void aCrashBetweenPublishAndMarkBecomesADuplicateNotALoss() {
    outbox.publish(TOPIC, Fixtures.event(CREATED, 1));
    AtomicBoolean crash = new AtomicBoolean(true);
    OutboxStore crashing =
        new DelegatingStore(store) {
          @Override
          public void markPublished(Collection<UUID> ids) {
            if (crash.getAndSet(false)) {
              throw new IllegalStateException("task killed after the broker accepted the message");
            }
            super.markPublished(ids);
          }
        };
    OutboxRelay relay = relay(crashing);

    assertThrows(IllegalStateException.class, relay::runOnce);
    assertEquals(1, broker.depth("all"), "the broker already has it");
    assertEquals(0, relay.runOnce(), "while the lease lasts nobody else takes the message");

    store.setClock(Clock.fixed(Instant.now().plus(Duration.ofMinutes(1)), ZoneOffset.UTC));
    assertEquals(1, relay.runOnce(), "after the lease expires it is published again");
    assertEquals(2, broker.depth("all"), "at-least-once: the consumer's inbox removes the duplicate");
    assertEquals(0, store.stats().pending());
  }

  @Test
  void theLoopDeliversInTheBackgroundAndStopsCleanly() {
    OutboxRelay relay = relay(store);
    relay.start();
    for (int i = 0; i < 30; i++) {
      outbox.publish(TOPIC, Fixtures.event(CREATED, i));
    }
    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth("all") == 30);
    assertTrue(relay.isAlive());
    assertTrue(Duration.between(relay.lastBeat(), Instant.now()).toMillis() < 2_000);

    outbox.publish(TOPIC, Fixtures.event(CREATED, 99));
    assertTrue(relay.stop(Duration.ofSeconds(5)), "stop flushes what is pending");
    assertEquals(31, broker.depth("all"));
    assertTrue(!relay.isAlive());
  }

  /** Lets a test override one method of the store. */
  private static class DelegatingStore implements OutboxStore {
    private final OutboxStore delegate;

    DelegatingStore(OutboxStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public void add(Collection<OutboxMessage> messages) {
      delegate.add(messages);
    }

    @Override
    public List<OutboxMessage> claim(int limit, Duration lease) {
      return delegate.claim(limit, lease);
    }

    @Override
    public void markPublished(Collection<UUID> ids) {
      delegate.markPublished(ids);
    }

    @Override
    public void reschedule(UUID id, String error, Duration delay) {
      delegate.reschedule(id, error, delay);
    }

    @Override
    public void park(UUID id, String error) {
      delegate.park(id, error);
    }

    @Override
    public OutboxStats stats() {
      return delegate.stats();
    }

    @Override
    public int purgePublished(Duration olderThan, int limit) {
      return delegate.purgePublished(olderThan, limit);
    }
  }
}
