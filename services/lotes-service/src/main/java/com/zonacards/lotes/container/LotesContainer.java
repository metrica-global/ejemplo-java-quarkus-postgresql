package com.zonacards.lotes.container;

import com.zonacards.dbx.Db;
import com.zonacards.eventx.EventCodec;
import com.zonacards.eventx.EventFactory;
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
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.lotes.LoteRepository;
import com.zonacards.lotes.infra.PostgresLoteRepository;
import com.zonacards.lotes.srv.LoteEventHandlers;
import com.zonacards.lotes.srv.LoteService;
import com.zonacards.server.EventxConfigs;
import com.zonacards.server.MessagingConfig;
import com.zonacards.server.MicrometerEventxMetrics;
import com.zonacards.server.OtelEventTracing;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Composition root of the lotes service: the only class that knows every piece and how they fit.
 * Read it top to bottom to see the hexagon being assembled: adapters first, then the services
 * that depend on ports, then the event loops.
 *
 * <p>The domain and the services carry no framework annotation. CDI is used here, and only here,
 * as the place to write {@code new}: each method is a line of wiring you can step through.
 */
public class LotesContainer {

  public static final String SERVICE = "lotes";
  public static final String SOURCE = "/zonacards/lotes";
  public static final String QUEUE_INBOX = "lotes-inbox";

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
  LoteRepository loteRepository(Db db) {
    return new PostgresLoteRepository(db);
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
  LoteService loteService(LoteRepository repository, UnitOfWork uow, Outbox outbox, EventFactory events, Clock clock) {
    return new LoteService(repository, uow, outbox, events, clock);
  }

  @Produces
  @Singleton
  LoteEventHandlers loteEventHandlers(LoteRepository repository, Outbox outbox, EventFactory events, Clock clock) {
    return new LoteEventHandlers(repository, outbox, events, clock);
  }

  // --- event loops ---------------------------------------------------------------------------

  @Produces
  @Singleton
  MessageDispatcher messageDispatcher(
      LoteEventHandlers handlers, Inbox inbox, UnitOfWork uow, EventCodec codec, OtelEventTracing tracing) {
    return new MessageDispatcher(SERVICE, handlers.register(new HandlerRegistry()), inbox, uow, codec, tracing);
  }

  @Produces
  @Singleton
  EventxRuntime eventxRuntime(
      MessagingConfig config,
      SnsClient sns,
      SqsClient sqs,
      OutboxStore store,
      Inbox inbox,
      UnitOfWork uow,
      MessageDispatcher dispatcher,
      MicrometerEventxMetrics metrics) {
    Destinations destinations = new Destinations(config.topics(), config.queues());
    OutboxRelay relay =
        config.relay().enabled()
            ? new OutboxRelay(store, new AwsMessageSender(sns, sqs, destinations), uow, inbox, metrics, EventxConfigs.relay(config))
            : null;
    List<QueueConsumer> consumers =
        config.consumer().enabled()
            ? List.of(
                new QueueConsumer(
                    EventxConfigs.consumer(config, QUEUE_INBOX), new SqsMessageReceiver(sqs, destinations), dispatcher, metrics))
            : List.of();
    return new EventxRuntime(relay, consumers);
  }
}
