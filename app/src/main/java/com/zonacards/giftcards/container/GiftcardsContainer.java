package com.zonacards.giftcards.container;

import com.zonacards.asyncx.ComputePool;
import com.zonacards.dbx.Db;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.EventxMetrics;
import com.zonacards.eventx.EventxRuntime;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.Inbox;
import com.zonacards.eventx.MessageDispatcher;
import com.zonacards.eventx.Outbox;
import com.zonacards.eventx.OutboxRelay;
import com.zonacards.eventx.OutboxStore;
import com.zonacards.eventx.QueueConsumer;
import com.zonacards.eventx.TransactionalOutbox;
import com.zonacards.eventx.aws.AwsMessageSender;
import com.zonacards.eventx.aws.Destinations;
import com.zonacards.eventx.aws.SqsMessageReceiver;
import com.zonacards.eventx.postgres.PostgresInbox;
import com.zonacards.eventx.postgres.PostgresOutboxStore;
import com.zonacards.giftcards.CodigoProtector;
import com.zonacards.giftcards.CvvHasher;
import com.zonacards.giftcards.GiftCardRepository;
import com.zonacards.giftcards.PlanRepository;
import com.zonacards.giftcards.events.GeneracionCommands;
import com.zonacards.giftcards.events.GiftcardsEvents;
import com.zonacards.giftcards.infra.AesGcmCodigoProtector;
import com.zonacards.giftcards.infra.Argon2idCvvHasher;
import com.zonacards.giftcards.infra.PostgresGiftCardRepository;
import com.zonacards.giftcards.infra.PostgresPlanRepository;
import com.zonacards.giftcards.srv.GeneracionHandlers;
import com.zonacards.giftcards.srv.GiftCardFactory;
import com.zonacards.giftcards.srv.GiftCardService;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.server.EventxConfigs;
import com.zonacards.server.MessagingConfig;
import com.zonacards.server.MicrometerEventxMetrics;
import com.zonacards.server.OtelEventTracing;
import com.zonacards.server.Preflight;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Composition root of the giftcards module. Same structure as the other module: a check of what
 * the deployment provides, adapters, services, event loops. The one difference is the second consumer, on the work queue, whose
 * backlog is the signal this module scales on when it is deployed as its own service.
 *
 * <p>Like every module container, it is created only in a deployment that serves the module.
 */
@Singleton
public class GiftcardsContainer {

  /** Name of the module: also its datasource, its schema and the consumer id of its inbox. */
  public static final String MODULE = "giftcards";

  public static final String API = "/api/v1/giftcards";
  public static final String SOURCE = "/zonacards/giftcards";
  public static final String QUEUE_INBOX = "giftcards-inbox";

  private final GiftCardService giftCardService;
  private final ComputePool cpu;
  private final EventxRuntime eventx;

  GiftcardsContainer(
      @DataSource(MODULE) InjectableInstance<AgroalDataSource> dataSource,
      GiftcardsConfig config,
      UnitOfWork uow,
      Clock clock,
      EventCodec codec,
      OtelEventTracing tracing,
      MessagingConfig messaging,
      SnsClient sns,
      SqsClient sqs,
      MeterRegistry meters) {

    // --- was this deployment given what the module needs? -------------------------------------
    // Checked before anything is built. The card keys are secrets that only a deployment serving
    // this module receives; without them, or without its queues, the task would start, answer
    // its health check and fail on the first card.
    boolean publishes = messaging.relay().enabled();
    boolean consumes = messaging.consumer().enabled();
    Destinations destinations = new Destinations(messaging.topics(), messaging.queues());
    SqsMessageReceiver receiver = new SqsMessageReceiver(sqs, destinations);
    Preflight preflight =
        Preflight.of(MODULE)
            .require("ZC_GIFTCARDS_CODIGO_KEY (the key that encrypts card codes)", config.codigo().key().isPresent())
            .require("ZC_GIFTCARDS_CODIGO_HMAC_KEY (the key of the blind index)", config.codigo().hmacKey().isPresent());
    if (publishes) {
      preflight
          .check("topic " + GiftcardsEvents.TOPIC, () -> destinations.topicArn(GiftcardsEvents.TOPIC))
          .check("queue " + GeneracionCommands.QUEUE, () -> destinations.queueUrl(GeneracionCommands.QUEUE));
    }
    if (consumes) {
      preflight
          .check("queue " + QUEUE_INBOX, () -> receiver.verify(QUEUE_INBOX))
          .check("queue " + GeneracionCommands.QUEUE, () -> receiver.verify(GeneracionCommands.QUEUE));
    }
    preflight.verify();

    // --- driven adapters ---------------------------------------------------------------------
    // The module's own connection pool, with the module's own database role.
    Db db = new Db(dataSource.get());
    GiftCardRepository giftcards = new PostgresGiftCardRepository(db);
    PlanRepository planes = new PostgresPlanRepository(db);
    CodigoProtector protector =
        new AesGcmCodigoProtector(config.codigo().key().orElseThrow(), config.codigo().hmacKey().orElseThrow());
    CvvHasher hasher =
        new Argon2idCvvHasher(config.argon2().memoryKib(), config.argon2().iterations(), config.argon2().parallelism());
    this.cpu = new ComputePool("generacion", config.generacion().cpuThreads());
    OutboxStore outboxStore = new PostgresOutboxStore(db);
    Inbox inbox = new PostgresInbox(db);
    Outbox outbox = new TransactionalOutbox(outboxStore, codec);
    EventFactory events = new EventFactory(SOURCE, codec, clock, tracing);

    // --- application services ----------------------------------------------------------------
    GiftCardFactory factory = new GiftCardFactory(protector, hasher, new SecureRandom());
    this.giftCardService = new GiftCardService(giftcards, factory, uow, outbox, events, clock);
    GeneracionHandlers handlers =
        new GeneracionHandlers(planes, giftcards, factory, cpu, uow, outbox, events, clock, config.generacion().fragmentSize());

    // --- event loops -------------------------------------------------------------------------
    EventxMetrics metrics = new MicrometerEventxMetrics(meters, MODULE);
    MessageDispatcher dispatcher =
        new MessageDispatcher(MODULE, handlers.register(new HandlerRegistry()), inbox, uow, codec, tracing);
    OutboxRelay relay =
        publishes
            ? new OutboxRelay(
                outboxStore, new AwsMessageSender(sns, sqs, destinations), uow, inbox, metrics, EventxConfigs.relay(messaging))
            : null;
    if (!consumes) {
      this.eventx = new EventxRuntime(relay, List.of());
      return;
    }
    // Events from other modules: light, transactional.
    QueueConsumer eventos = new QueueConsumer(EventxConfigs.consumer(messaging, QUEUE_INBOX), receiver, dispatcher, metrics);
    // This module's own work: heavy, CPU-bound, the queue the autoscaling policy watches.
    QueueConsumer trabajo =
        new QueueConsumer(
            EventxConfigs.consumer(
                messaging, GeneracionCommands.QUEUE, config.generacion().concurrency(), config.generacion().visibilityTimeout()),
            receiver,
            dispatcher,
            metrics);
    this.eventx = new EventxRuntime(relay, List.of(eventos, trabajo));
  }

  /** What the REST adapter of the module needs. */
  @Produces
  @Singleton
  GiftCardService giftCardService() {
    return giftCardService;
  }

  /** The module's background loops, for the application lifecycle. */
  public EventxRuntime eventx() {
    return eventx;
  }

  @PreDestroy
  void close() {
    cpu.close();
  }
}
