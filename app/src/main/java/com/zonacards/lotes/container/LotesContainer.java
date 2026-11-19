package com.zonacards.lotes.container;

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
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.lotes.LoteRepository;
import com.zonacards.lotes.events.LoteEvents;
import com.zonacards.lotes.infra.PostgresLoteRepository;
import com.zonacards.lotes.srv.LoteEventHandlers;
import com.zonacards.lotes.srv.LoteService;
import com.zonacards.server.EventxConfigs;
import com.zonacards.server.MessagingConfig;
import com.zonacards.server.MicrometerEventxMetrics;
import com.zonacards.server.OtelEventTracing;
import com.zonacards.server.Preflight;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Composition root of the lotes module: the only class that knows every piece of it and how
 * they fit. Read the constructor top to bottom to see the hexagon being assembled: a check of
 * what the deployment provides, then adapters, then the services that depend on ports, then the
 * event loops.
 *
 * <p>The domain and the services carry no framework annotation. The object graph is built here
 * with plain {@code new}, one line of wiring at a time. CDI only hands in the pieces of the
 * platform (this module's datasource, the unit of work, the AWS clients) and creates this class
 * the first time the module is needed. A deployment that does not serve the module never
 * creates it, so it needs none of the module's credentials or queues.
 */
@Singleton
public class LotesContainer {

  /** Name of the module: also its datasource, its schema and the consumer id of its inbox. */
  public static final String MODULE = "lotes";

  public static final String API = "/api/v1/lotes";
  public static final String SOURCE = "/zonacards/lotes";
  public static final String QUEUE_INBOX = "lotes-inbox";

  private final LoteService loteService;
  private final EventxRuntime eventx;

  LotesContainer(
      @DataSource(MODULE) InjectableInstance<AgroalDataSource> dataSource,
      UnitOfWork uow,
      Clock clock,
      EventCodec codec,
      OtelEventTracing tracing,
      MessagingConfig messaging,
      SnsClient sns,
      SqsClient sqs,
      MeterRegistry meters) {

    // --- was this deployment given what the module needs? -------------------------------------
    // Checked before anything is built: a task without its queue would start, answer its health
    // check and process nothing.
    boolean publishes = messaging.relay().enabled();
    boolean consumes = messaging.consumer().enabled();
    Destinations destinations = new Destinations(messaging.topics(), messaging.queues());
    SqsMessageReceiver receiver = new SqsMessageReceiver(sqs, destinations);
    Preflight preflight = Preflight.of(MODULE);
    if (publishes) {
      preflight.check("topic " + LoteEvents.TOPIC, () -> destinations.topicArn(LoteEvents.TOPIC));
    }
    if (consumes) {
      preflight.check("queue " + QUEUE_INBOX, () -> receiver.verify(QUEUE_INBOX));
    }
    preflight.verify();

    // --- driven adapters ---------------------------------------------------------------------
    // The module's own connection pool, with the module's own database role.
    Db db = new Db(dataSource.get());
    LoteRepository repository = new PostgresLoteRepository(db);
    OutboxStore outboxStore = new PostgresOutboxStore(db);
    Inbox inbox = new PostgresInbox(db);
    Outbox outbox = new TransactionalOutbox(outboxStore, codec);
    EventFactory events = new EventFactory(SOURCE, codec, clock, tracing);

    // --- application services ----------------------------------------------------------------
    this.loteService = new LoteService(repository, uow, outbox, events, clock);
    LoteEventHandlers handlers = new LoteEventHandlers(repository, outbox, events, clock);

    // --- event loops -------------------------------------------------------------------------
    EventxMetrics metrics = new MicrometerEventxMetrics(meters, MODULE);
    MessageDispatcher dispatcher =
        new MessageDispatcher(MODULE, handlers.register(new HandlerRegistry()), inbox, uow, codec, tracing);
    OutboxRelay relay =
        publishes
            ? new OutboxRelay(
                outboxStore, new AwsMessageSender(sns, sqs, destinations), uow, inbox, metrics, EventxConfigs.relay(messaging))
            : null;
    List<QueueConsumer> consumers =
        consumes
            ? List.of(new QueueConsumer(EventxConfigs.consumer(messaging, QUEUE_INBOX), receiver, dispatcher, metrics))
            : List.of();
    this.eventx = new EventxRuntime(relay, consumers);
  }

  /** What the REST adapter of the module needs. */
  @Produces
  @Singleton
  LoteService loteService() {
    return loteService;
  }

  /** The module's background loops, for the application lifecycle. */
  public EventxRuntime eventx() {
    return eventx;
  }
}
