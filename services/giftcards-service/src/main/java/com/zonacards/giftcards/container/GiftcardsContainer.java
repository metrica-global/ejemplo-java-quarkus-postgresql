package com.zonacards.giftcards.container;

import com.zonacards.asyncx.ComputePool;
import com.zonacards.dbx.Db;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventFactory;
import com.zonacards.eventx.EventxRuntime;
import com.zonacards.eventx.HandlerRegistry;
import com.zonacards.eventx.Inbox;
import com.zonacards.eventx.MessageDispatcher;
import com.zonacards.eventx.MessageReceiver;
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
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Composition root of the giftcards service. Same structure as the other domain: adapters,
 * services, event loops. The one difference is the second consumer, on the work queue, whose
 * backlog is what the service scales on.
 */
public class GiftcardsContainer {

  public static final String SERVICE = "giftcards";
  public static final String SOURCE = "/zonacards/giftcards";
  public static final String QUEUE_INBOX = "giftcards-inbox";

  // --- driven adapters -----------------------------------------------------------------------

  @Produces
  @Singleton
  Clock clock() {
    return Clock.systemUTC();
  }

  @Produces
  @Singleton
  Db db(DataSource dataSource) {
    return new Db(dataSource);
  }

  @Produces
  @Singleton
  GiftCardRepository giftCardRepository(Db db) {
    return new PostgresGiftCardRepository(db);
  }

  @Produces
  @Singleton
  PlanRepository planRepository(Db db) {
    return new PostgresPlanRepository(db);
  }

  @Produces
  @Singleton
  CodigoProtector codigoProtector(GiftcardsConfig config) {
    return new AesGcmCodigoProtector(config.codigo().key().orElse(null), config.codigo().hmacKey().orElse(null));
  }

  @Produces
  @Singleton
  CvvHasher cvvHasher(GiftcardsConfig config) {
    return new Argon2idCvvHasher(config.argon2().memoryKib(), config.argon2().iterations(), config.argon2().parallelism());
  }

  @Produces
  @Singleton
  ComputePool computePool(GiftcardsConfig config) {
    return new ComputePool("generacion", config.generacion().cpuThreads());
  }

  void close(@Disposes ComputePool pool) {
    pool.close();
  }

  @Produces
  @Singleton
  EventCodec eventCodec() {
    return new EventCodec();
  }

  @Produces
  @Singleton
  OutboxStore outboxStore(Db db) {
    return new PostgresOutboxStore(db);
  }

  @Produces
  @Singleton
  Inbox inbox(Db db) {
    return new PostgresInbox(db);
  }

  @Produces
  @Singleton
  Outbox outbox(OutboxStore store, EventCodec codec) {
    return new TransactionalOutbox(store, codec);
  }

  @Produces
  @Singleton
  EventFactory eventFactory(EventCodec codec, Clock clock, OtelEventTracing tracing) {
    return new EventFactory(SOURCE, codec, clock, tracing);
  }

  // --- application services ------------------------------------------------------------------

  @Produces
  @Singleton
  GiftCardFactory giftCardFactory(CodigoProtector protector, CvvHasher hasher) {
    return new GiftCardFactory(protector, hasher, new SecureRandom());
  }

  @Produces
  @Singleton
  GiftCardService giftCardService(
      GiftCardRepository repository, GiftCardFactory factory, UnitOfWork uow, Outbox outbox, EventFactory events, Clock clock) {
    return new GiftCardService(repository, factory, uow, outbox, events, clock);
  }

  @Produces
  @Singleton
  GeneracionHandlers generacionHandlers(
      PlanRepository planes,
      GiftCardRepository giftcards,
      GiftCardFactory factory,
      ComputePool cpu,
      UnitOfWork uow,
      Outbox outbox,
      EventFactory events,
      Clock clock,
      GiftcardsConfig config) {
    return new GeneracionHandlers(planes, giftcards, factory, cpu, uow, outbox, events, clock, config.generacion().fragmentSize());
  }

  // --- event loops ---------------------------------------------------------------------------

  @Produces
  @Singleton
  MessageDispatcher messageDispatcher(
      GeneracionHandlers handlers, Inbox inbox, UnitOfWork uow, EventCodec codec, OtelEventTracing tracing) {
    return new MessageDispatcher(SERVICE, handlers.register(new HandlerRegistry()), inbox, uow, codec, tracing);
  }

  @Produces
  @Singleton
  EventxRuntime eventxRuntime(
      MessagingConfig messaging,
      GiftcardsConfig config,
      SnsClient sns,
      SqsClient sqs,
      OutboxStore store,
      Inbox inbox,
      UnitOfWork uow,
      MessageDispatcher dispatcher,
      MicrometerEventxMetrics metrics) {
    Destinations destinations = new Destinations(messaging.topics(), messaging.queues());
    OutboxRelay relay =
        messaging.relay().enabled()
            ? new OutboxRelay(store, new AwsMessageSender(sns, sqs, destinations), uow, inbox, metrics, EventxConfigs.relay(messaging))
            : null;
    if (!messaging.consumer().enabled()) {
      return new EventxRuntime(relay, List.of());
    }
    MessageReceiver receiver = new SqsMessageReceiver(sqs, destinations);
    // Events from other domains: light, transactional.
    QueueConsumer eventos = new QueueConsumer(EventxConfigs.consumer(messaging, QUEUE_INBOX), receiver, dispatcher, metrics);
    // This domain's own work: heavy, CPU-bound, the queue the autoscaling policy watches.
    QueueConsumer trabajo =
        new QueueConsumer(
            EventxConfigs.consumer(
                messaging, GeneracionCommands.QUEUE, config.generacion().concurrency(), config.generacion().visibilityTimeout()),
            receiver,
            dispatcher,
            metrics);
    return new EventxRuntime(relay, List.of(eventos, trabajo));
  }
}
