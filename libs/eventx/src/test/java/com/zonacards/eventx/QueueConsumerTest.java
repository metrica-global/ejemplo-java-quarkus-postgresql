package com.zonacards.eventx;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.eventx.memory.InMemoryBroker;
import com.zonacards.eventx.memory.InMemoryInbox;
import com.zonacards.eventx.memory.InMemoryOutboxStore;
import com.zonacards.eventx.memory.InMemoryUnitOfWork;
import com.zonacards.kernel.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueConsumerTest {

  private static final String QUEUE = "work";
  private static final String TYPE = "zonacards.test.do-work.v1";

  private InMemoryBroker broker;
  private InMemoryOutboxStore store;
  private Outbox outbox;
  private OutboxRelay relay;
  private InMemoryInbox inbox;
  private HandlerRegistry registry;
  private QueueConsumer consumer;

  @BeforeEach
  void setUp() {
    broker = new InMemoryBroker().queue(QUEUE, 3);
    store = new InMemoryOutboxStore();
    outbox = new TransactionalOutbox(store, Fixtures.CODEC);
    relay = new OutboxRelay(store, broker, UnitOfWork.direct(), null, EventxMetrics.NONE, Fixtures.relayConfig());
    inbox = new InMemoryInbox();
    registry = new HandlerRegistry();
  }

  @AfterEach
  void tearDown() {
    if (consumer != null) {
      consumer.stop(Duration.ofSeconds(5));
    }
  }

  private QueueConsumer start(QueueConsumer.Config config) {
    MessageDispatcher dispatcher =
        new MessageDispatcher("test-consumer", registry, inbox, new InMemoryUnitOfWork(), Fixtures.CODEC, ConsumeInterceptor.NONE);
    consumer = new QueueConsumer(config, broker, dispatcher, EventxMetrics.NONE);
    consumer.start();
    return consumer;
  }

  private void send(EventEnvelope... commands) {
    outbox.sendAll(QUEUE, List.of(commands));
    relay.runOnce();
  }

  @Test
  void aHandledMessageIsDeletedFromTheQueue() {
    Set<Integer> handled = ConcurrentHashMap.newKeySet();
    registry.on(TYPE, ctx -> handled.add(ctx.data(Fixtures.Payload.class).n()));
    start(Fixtures.consumerConfig(QUEUE, 4));

    send(Fixtures.event(TYPE, 1), Fixtures.event(TYPE, 2), Fixtures.event(TYPE, 3));

    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth(QUEUE) == 0);
    assertEquals(Set.of(1, 2, 3), handled);
  }

  @Test
  void theSameEventDeliveredTwiceIsHandledOnce() {
    AtomicInteger calls = new AtomicInteger();
    registry.on(TYPE, ctx -> calls.incrementAndGet());
    start(Fixtures.consumerConfig(QUEUE, 4));

    EventEnvelope event = Fixtures.event(TYPE, 1);
    String body = Fixtures.CODEC.encode(event);
    broker.inject(QUEUE, body, Map.of("type", TYPE));
    broker.inject(QUEUE, body, Map.of("type", TYPE));
    broker.inject(QUEUE, body, Map.of("type", TYPE));

    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth(QUEUE) == 0);
    assertEquals(1, calls.get(), "duplicates are acknowledged without running the handler");
  }

  @Test
  void aFailingMessageIsRedeliveredAndFinallyDeadLettered() {
    AtomicInteger attempts = new AtomicInteger();
    registry.on(
        TYPE,
        ctx -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("downstream unavailable");
        });
    start(Fixtures.consumerConfig(QUEUE, 2));

    send(Fixtures.event(TYPE, 1));

    await().atMost(Duration.ofSeconds(10)).until(() -> broker.depth(QUEUE + "-dlq") == 1);
    assertEquals(3, attempts.get(), "delivered maxReceiveCount times before the dead-letter queue");
    assertEquals(0, broker.depth(QUEUE));
  }

  @Test
  void aMessageThatFailedOnceIsHandledWhenTheCauseGoesAway() {
    AtomicInteger attempts = new AtomicInteger();
    registry.on(
        TYPE,
        ctx -> {
          if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("first attempt fails");
          }
        });
    start(Fixtures.consumerConfig(QUEUE, 2));

    send(Fixtures.event(TYPE, 1));

    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth(QUEUE) == 0);
    assertEquals(2, attempts.get());
    assertEquals(0, broker.depth(QUEUE + "-dlq"));
  }

  @Test
  void aFailedHandlerLeavesNoTraceSoItsEventsAreNotPublished() {
    AtomicInteger attempts = new AtomicInteger();
    registry.on(
        TYPE,
        ctx -> {
          outbox.send(QUEUE + "-dlq", Fixtures.event("zonacards.test.side-effect.v1", attempts.get()));
          if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("fails after writing to the outbox");
          }
        });
    start(Fixtures.consumerConfig(QUEUE, 1));

    send(Fixtures.event(TYPE, 1));

    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth(QUEUE) == 0);
    relay.runOnce();
    assertEquals(2, attempts.get());
    assertEquals(1, broker.depth(QUEUE + "-dlq"), "only the committed attempt emitted its message");
  }

  @Test
  void aPoisonMessageEndsInTheDeadLetterQueueAndUnknownTypesAreAcknowledged() {
    registry.on(TYPE, ctx -> {});
    start(Fixtures.consumerConfig(QUEUE, 2));

    broker.inject(QUEUE, "this is not an event", Map.of());
    send(Fixtures.event("zonacards.test.nobody-listens.v1", 1));

    await().atMost(Duration.ofSeconds(10)).until(() -> broker.depth(QUEUE + "-dlq") == 1 && broker.depth(QUEUE) == 0);
    assertEquals(List.of("this is not an event"), broker.bodies(QUEUE + "-dlq"));
  }

  @Test
  void messagesInFlightNeverExceedTheConcurrencyLimit() {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    AtomicInteger done = new AtomicInteger();
    registry.on(
        TYPE,
        ctx -> {
          peak.accumulateAndGet(running.incrementAndGet(), Math::max);
          sleep(15);
          running.decrementAndGet();
          done.incrementAndGet();
        });
    start(Fixtures.consumerConfig(QUEUE, 4));

    EventEnvelope[] commands = new EventEnvelope[40];
    for (int i = 0; i < commands.length; i++) {
      commands[i] = Fixtures.event(TYPE, i);
    }
    send(commands);

    await().atMost(Duration.ofSeconds(10)).until(() -> done.get() == 40);
    assertTrue(peak.get() <= 4, "peak in flight was " + peak.get());
    assertTrue(peak.get() >= 2, "it should actually run in parallel; peak was " + peak.get());
  }

  @Test
  void stoppingDrainsWhatIsInFlightAndLeavesTheRestInTheQueue() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger started = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();
    registry.on(
        TYPE,
        ctx -> {
          started.incrementAndGet();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finished.incrementAndGet();
        });
    QueueConsumer queueConsumer = start(Fixtures.consumerConfig(QUEUE, 2));
    send(Fixtures.event(TYPE, 1), Fixtures.event(TYPE, 2), Fixtures.event(TYPE, 3), Fixtures.event(TYPE, 4), Fixtures.event(TYPE, 5));
    await().atMost(Duration.ofSeconds(5)).until(() -> started.get() == 2);

    assertFalse(queueConsumer.stop(Duration.ofMillis(200)), "work is still running: stop reports it did not drain");

    release.countDown();
    assertTrue(queueConsumer.stop(Duration.ofSeconds(5)));
    assertEquals(2, finished.get(), "in-flight messages finished");
    assertEquals(2, started.get(), "nothing new was started after stop");
    assertEquals(3, broker.depth(QUEUE), "unstarted messages stay in the queue for another task");
    assertEquals(0, queueConsumer.inFlight());
  }

  @Test
  void aSlowHandlerKeepsItsMessageHidden() {
    AtomicInteger calls = new AtomicInteger();
    registry.on(
        TYPE,
        ctx -> {
          calls.incrementAndGet();
          sleep(700);
        });
    // Visibility timeout far shorter than the handler: without the extension it would be redelivered.
    start(new QueueConsumer.Config(QUEUE, 3, Duration.ofMillis(50), Duration.ofMillis(200), Fixtures.FAST));

    send(Fixtures.event(TYPE, 1));

    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth(QUEUE) == 0);
    assertEquals(1, calls.get());
  }

  @Test
  void aSelfManagedHandlerDoesItsExpensiveWorkOutsideTheTransaction() {
    AtomicInteger expensive = new AtomicInteger();
    AtomicInteger committed = new AtomicInteger();
    registry.onSelfManaged(
        TYPE,
        ctx -> {
          expensive.incrementAndGet();
          ctx.once(committed::incrementAndGet);
        });
    registry.on("zonacards.test.plain.v1", ctx -> assertThrows(IllegalStateException.class, () -> ctx.once(() -> {})));
    start(Fixtures.consumerConfig(QUEUE, 2));

    EventEnvelope command = Fixtures.event(TYPE, 1);
    String body = Fixtures.CODEC.encode(command);
    broker.inject(QUEUE, body, Map.of("type", TYPE));
    await().atMost(Duration.ofSeconds(5)).until(() -> committed.get() == 1);
    broker.inject(QUEUE, body, Map.of("type", TYPE));
    send(Fixtures.event("zonacards.test.plain.v1", 2));

    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth(QUEUE) == 0);
    assertEquals(2, expensive.get(), "the handler ran again for the duplicate");
    assertEquals(1, committed.get(), "but its effects were applied once");
    assertEquals(0, broker.depth(QUEUE + "-dlq"));
  }

  @Test
  void theHandlerActsAsTheSystemForTheTenantOfTheEvent() {
    AtomicInteger checked = new AtomicInteger();
    registry.on(
        TYPE,
        ctx -> {
          var auth = ctx.systemAuth("test-consumer", "things:write");
          assertTrue(auth.actor().isSystem());
          assertEquals(Fixtures.TENANT, auth.tenantId());
          assertTrue(auth.hasScope("things:write"));
          assertEquals(ctx.envelope().correlationid(), CorrelationContext.current());
          checked.incrementAndGet();
        });
    start(Fixtures.consumerConfig(QUEUE, 1));
    send(Fixtures.event(TYPE, 1));
    await().atMost(Duration.ofSeconds(5)).until(() -> broker.depth(QUEUE) == 0);
    assertEquals(1, checked.get());
    assertEquals(0, broker.depth(QUEUE + "-dlq"));
  }

  @Test
  void theRuntimeStopsConsumersBeforeTheRelaySoTheirLastEventsGetOut() {
    broker.topic("things-events").queue("audit", 3).subscribe("things-events", "audit");
    CountDownLatch entered = new CountDownLatch(1);
    registry.on(
        TYPE,
        ctx -> {
          entered.countDown();
          sleep(300);
          outbox.publish("things-events", Fixtures.event("zonacards.test.done.v1", 1));
        });
    MessageDispatcher dispatcher =
        new MessageDispatcher("test-consumer", registry, inbox, new InMemoryUnitOfWork(), Fixtures.CODEC, ConsumeInterceptor.NONE);
    QueueConsumer queueConsumer = new QueueConsumer(Fixtures.consumerConfig(QUEUE, 2), broker, dispatcher, EventxMetrics.NONE);
    EventxRuntime runtime = new EventxRuntime(relay, List.of(queueConsumer));
    runtime.start();
    assertTrue(runtime.heartbeats().keySet().containsAll(Set.of("outbox-relay", "consumer-" + QUEUE)));
    assertTrue(runtime.heartbeats().values().stream().allMatch(beat -> beat.isAfter(Instant.now().minusSeconds(5))));

    outbox.send(QUEUE, Fixtures.event(TYPE, 1));
    await().atMost(Duration.ofSeconds(5)).until(() -> entered.getCount() == 0);
    runtime.stop(Duration.ofSeconds(10));

    assertEquals(0, broker.depth(QUEUE), "the in-flight message finished and was deleted");
    assertEquals(1, broker.depth("audit"), "the event it produced was published before shutdown ended");
    assertTrue(runtime.heartbeats().values().stream().allMatch(Instant.EPOCH::equals), "stopped loops report no heartbeat");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
