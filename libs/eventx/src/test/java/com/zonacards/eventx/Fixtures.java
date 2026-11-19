package com.zonacards.eventx;

import com.zonacards.asyncx.Backoff;
import com.zonacards.kernel.TenantId;
import java.time.Duration;

final class Fixtures {

  static final EventCodec CODEC = new EventCodec();
  static final EventFactory FACTORY = new EventFactory("/test", CODEC);
  static final TenantId TENANT = TenantId.of("pe");
  static final Backoff FAST = new Backoff(Duration.ofMillis(5), Duration.ofMillis(20), 2, 0);

  record Payload(String name, int n) {}

  private Fixtures() {}

  static EventEnvelope event(String type, int n) {
    return FACTORY.create(type, TENANT, "subject-" + n, new Payload("p" + n, n));
  }

  static OutboxRelay.Config relayConfig() {
    return new OutboxRelay.Config(
        50, Duration.ofSeconds(30), Duration.ofMillis(5), FAST, Duration.ofDays(1), Duration.ofDays(1), Duration.ofMillis(10));
  }

  static QueueConsumer.Config consumerConfig(String queue, int concurrency) {
    return new QueueConsumer.Config(queue, concurrency, Duration.ofMillis(50), Duration.ofSeconds(5), FAST);
  }
}
