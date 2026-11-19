package com.zonacards.server;

import com.zonacards.asyncx.Backoff;
import com.zonacards.eventx.OutboxRelay;
import com.zonacards.eventx.QueueConsumer;
import java.time.Duration;

/** Translates the service configuration into the settings of the eventx loops. */
public final class EventxConfigs {

  private EventxConfigs() {}

  public static OutboxRelay.Config relay(MessagingConfig config) {
    OutboxRelay.Config defaults = OutboxRelay.Config.defaults();
    return new OutboxRelay.Config(
        config.relay().batchSize(),
        config.relay().lease(),
        config.relay().pollInterval(),
        defaults.retryBackoff(),
        defaults.retention(),
        defaults.inboxRetention(),
        defaults.housekeepingInterval());
  }

  public static QueueConsumer.Config consumer(MessagingConfig config, String queue) {
    return consumer(config, queue, config.consumer().concurrency(), config.consumer().visibilityTimeout());
  }

  public static QueueConsumer.Config consumer(
      MessagingConfig config, String queue, int concurrency, Duration visibilityTimeout) {
    return new QueueConsumer.Config(
        queue,
        concurrency,
        config.consumer().waitTime(),
        visibilityTimeout,
        Backoff.exponential(Duration.ofSeconds(2), Duration.ofMinutes(5)));
  }
}
