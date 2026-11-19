package com.zonacards.eventx.aws;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.asyncx.Backoff;
import com.zonacards.dbx.Db;
import com.zonacards.dbx.LocalUnitOfWork;
import com.zonacards.eventx.ConsumeInterceptor;
import com.zonacards.eventx.DestinationKind;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.EventxException;
import com.zonacards.eventx.EventxMetrics;
import com.zonacards.eventx.EventxRuntime;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.MessageDispatcher;
import com.zonacards.eventx.Outbox;
import com.zonacards.eventx.OutboxMessage;
import com.zonacards.eventx.OutboxRelay;
import com.zonacards.eventx.QueueConsumer;
import com.zonacards.eventx.ReceivedMessage;
import com.zonacards.eventx.SendResult;
import com.zonacards.eventx.TransactionalOutbox;
import com.zonacards.eventx.postgres.EventxSchema;
import com.zonacards.eventx.postgres.PostgresInbox;
import com.zonacards.eventx.postgres.PostgresOutboxStore;
import com.zonacards.kernel.TenantId;
import com.zonacards.testkit.FlociFixture;
import com.zonacards.testkit.PostgresFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

/** The AWS adapter against the SNS/SQS emulator, and the whole pipeline with a real database. */
class AwsMessagingIT {

  private static final String CREATED = "zonacards.test.created.v1";
  private static final String UPDATED = "zonacards.test.updated.v1";
  private static final String DELETED = "zonacards.test.deleted.v1";
  private static final String WORK = "zonacards.test.do-work.v1";
  private static final TenantId TENANT = TenantId.of("pe");
  private static final EventCodec CODEC = new EventCodec();
  private static final EventFactory FACTORY = new EventFactory("/test", CODEC);

  record Payload(int n) {}

  static FlociFixture floci;
  static Destinations destinations;
  static AwsMessageSender sender;
  static SqsMessageReceiver receiver;
  static String filteredUrl;
  static String workUrl;

  @BeforeAll
  static void topology() {
    floci = FlociFixture.shared();
    String run = UUID.randomUUID().toString().substring(0, 8);
    String topicArn = floci.createTopic("it-" + run + "-things-events");
    filteredUrl = floci.createQueue("it-" + run + "-things-inbox", 2, 2);
    workUrl = floci.createQueue("it-" + run + "-things-work", 2, 2);
    floci.subscribe(topicArn, filteredUrl, List.of(CREATED, DELETED));
    destinations =
        new Destinations(Map.of("things-events", topicArn), Map.of("things-inbox", filteredUrl, "things-work", workUrl));
    sender = new AwsMessageSender(floci.sns(), floci.sqs(), destinations);
    receiver = new SqsMessageReceiver(floci.sqs(), destinations);
  }

  private static EventEnvelope event(String type, int n) {
    return FACTORY.create(type, TENANT, "s-" + n, new Payload(n));
  }

  private static OutboxMessage message(DestinationKind kind, String destination, EventEnvelope e) {
    return new OutboxMessage(UUID.fromString(e.id()), kind, destination, e.type(), e.tenantid(), CODEC.encode(e), 1, Instant.now());
  }

  @Test
  void eventsReachOnlyTheSubscribersWhoseFilterMatches() {
    List<OutboxMessage> batch =
        List.of(
            message(DestinationKind.TOPIC, "things-events", event(CREATED, 1)),
            message(DestinationKind.TOPIC, "things-events", event(UPDATED, 2)),
            message(DestinationKind.TOPIC, "things-events", event(DELETED, 3)));

    SendResult result = sender.send(DestinationKind.TOPIC, "things-events", batch);
    assertEquals(3, result.sent().size(), "failed: " + result.failed());

    List<Message> received = floci.drain(filteredUrl, 2, Duration.ofSeconds(20));
    assertEquals(2, received.size());
    for (Message m : received) {
      EventEnvelope decoded = CODEC.decode(m.body());
      assertTrue(List.of(CREATED, DELETED).contains(decoded.type()), "raw delivery: the body is the event itself");
      assertEquals(decoded.type(), m.messageAttributes().get("type").stringValue());
      assertEquals("pe", m.messageAttributes().get("tenantid").stringValue());
    }
    assertTrue(floci.drain(filteredUrl, 1, Duration.ofSeconds(2)).isEmpty(), "the 'updated' event was filtered out");
  }

  @Test
  void commandsGoStraightToTheWorkQueueAndCanBeGivenBack() {
    List<OutboxMessage> batch = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      batch.add(message(DestinationKind.QUEUE, "things-work", event(WORK, i)));
    }
    assertEquals(10, sender.send(DestinationKind.QUEUE, "things-work", batch).sent().size());
    await().atMost(Duration.ofSeconds(10)).until(() -> floci.depth(workUrl) == 10);

    List<ReceivedMessage> first = receiver.receive("things-work", 1, Duration.ofSeconds(2), Duration.ofSeconds(30));
    assertEquals(1, first.size());
    assertEquals(1, first.get(0).receiveCount());
    assertEquals(WORK, first.get(0).attributes().get("type"));

    // Visibility 0 is how a stopping consumer returns a message it did not start.
    receiver.changeVisibility("things-work", first.get(0), Duration.ZERO);
    List<ReceivedMessage> all = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (all.size() < 10 && System.nanoTime() < deadline) {
      for (ReceivedMessage m : receiver.receive("things-work", 10, Duration.ofSeconds(1), Duration.ofSeconds(30))) {
        all.add(m);
        receiver.delete("things-work", m);
      }
    }
    assertEquals(10, all.size());
    assertTrue(all.stream().anyMatch(m -> m.receiveCount() == 2), "the returned message came back with receive count 2");
  }

  @Test
  void aDestinationThatWasNotConfiguredIsAnErrorNotASilentDrop() {
    OutboxMessage m = message(DestinationKind.TOPIC, "nobody-configured-this", event(CREATED, 1));
    assertThrows(EventxException.class, () -> sender.send(DestinationKind.TOPIC, "nobody-configured-this", List.of(m)));
    assertThrows(EventxException.class, () -> receiver.receive("nobody-configured-this", 1, Duration.ZERO, Duration.ofSeconds(1)));
  }

  @Test
  void stoppingAConsumerCutsItsLongPollInsteadOfWaitingForIt() {
    String run = UUID.randomUUID().toString().substring(0, 8);
    String idleUrl = floci.createQueue("it-" + run + "-idle", 30, 5);
    SqsMessageReceiver idleReceiver = new SqsMessageReceiver(floci.sqs(), new Destinations(Map.of(), Map.of("idle", idleUrl)));
    MessageDispatcher dispatcher =
        new MessageDispatcher(
            "idle", new HandlerRegistry(), new com.zonacards.eventx.memory.InMemoryInbox(), com.zonacards.kernel.UnitOfWork.direct(), CODEC, ConsumeInterceptor.NONE);
    QueueConsumer consumer =
        new QueueConsumer(
            new QueueConsumer.Config("idle", 2, Duration.ofSeconds(20), Duration.ofSeconds(30), Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(2))),
            idleReceiver,
            dispatcher,
            EventxMetrics.NONE);
    consumer.start();
    sleep(1_500); // let it enter the 20 s long poll

    long started = System.nanoTime();
    boolean stopped = consumer.stop(Duration.ofSeconds(15));
    long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertTrue(stopped, "the consumer did not stop");
    assertTrue(millis < 5_000, "stop took " + millis + " ms: the long poll was not interrupted");
  }

  @Test
  void theWholePipelineDeliversEveryEventOnceAndDeadLettersWhatKeepsFailing() {
    PostgresFixture postgres = PostgresFixture.shared().bootstrapService("eventxaws", 40);
    postgres.executeAsOwner("eventxaws", "DROP TABLE IF EXISTS outbox, inbox, effect", EventxSchema.ddl(), "CREATE TABLE effect (n int PRIMARY KEY)");
    LocalUnitOfWork uow = new LocalUnitOfWork(postgres.appDataSource("eventxaws"));
    Db db = new Db(uow);
    PostgresOutboxStore store = new PostgresOutboxStore(db);
    PostgresInbox inbox = new PostgresInbox(db);
    Outbox outbox = new TransactionalOutbox(store, CODEC);

    String run = UUID.randomUUID().toString().substring(0, 8);
    String topicArn = floci.createTopic("it-" + run + "-pipeline-events");
    String inboxUrl = floci.createQueue("it-" + run + "-pipeline-inbox", 5, 2);
    floci.subscribe(topicArn, inboxUrl, List.of(CREATED));
    Destinations pipeline = new Destinations(Map.of("pipeline-events", topicArn), Map.of("pipeline-inbox", inboxUrl));

    AtomicInteger poisonAttempts = new AtomicInteger();
    HandlerRegistry registry =
        new HandlerRegistry()
            .on(
                CREATED,
                ctx -> {
                  int n = ctx.data(Payload.class).n();
                  if (n == 13) {
                    poisonAttempts.incrementAndGet();
                    throw new IllegalStateException("this one never succeeds");
                  }
                  db.update("INSERT INTO effect (n) VALUES (?)", n);
                });
    Backoff fast = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 0);
    OutboxRelay relay =
        new OutboxRelay(
            store,
            new AwsMessageSender(floci.sns(), floci.sqs(), pipeline),
            uow,
            inbox,
            EventxMetrics.NONE,
            new OutboxRelay.Config(50, Duration.ofSeconds(30), Duration.ofMillis(50), fast, Duration.ofDays(1), Duration.ofDays(1), Duration.ofSeconds(5)));
    QueueConsumer consumer =
        new QueueConsumer(
            new QueueConsumer.Config("pipeline-inbox", 8, Duration.ofSeconds(1), Duration.ofSeconds(5), fast),
            new SqsMessageReceiver(floci.sqs(), pipeline),
            new MessageDispatcher("pipeline", registry, inbox, uow, CODEC, ConsumeInterceptor.NONE),
            EventxMetrics.NONE);
    EventxRuntime runtime = new EventxRuntime(relay, List.of(consumer));
    runtime.start();
    try {
      uow.runInTx(
          () -> {
            for (int i = 0; i < 60; i++) {
              outbox.publish("pipeline-events", event(CREATED, i));
            }
            outbox.publish("pipeline-events", event(UPDATED, 999)); // nobody subscribed to this type
          });

      await().atMost(Duration.ofSeconds(60)).until(() -> uow.inTx(() -> db.queryLong("SELECT count(*) FROM effect")) == 59);
      String dlqUrl = floci.queueUrl("it-" + run + "-pipeline-inbox-dlq");
      await().atMost(Duration.ofSeconds(60)).until(() -> floci.depth(dlqUrl) == 1);
      assertEquals(2, poisonAttempts.get(), "tried maxReceiveCount times, then moved to the dead-letter queue");
      assertEquals(0, uow.inTx(() -> store.stats()).pending());
      assertEquals(59, uow.inTx(() -> db.queryLong("SELECT count(*) FROM inbox")), "the failed message left no inbox record");
      Message dead = floci.drain(dlqUrl, 1, Duration.ofSeconds(10)).get(0);
      assertEquals(13, CODEC.data(CODEC.decode(dead.body()), Payload.class).n(), "the dead letter keeps the full event for the redrive");
    } finally {
      runtime.stop(Duration.ofSeconds(20));
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
