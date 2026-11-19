package com.zonacards.server;

import com.zonacards.eventx.DestinationKind;
import com.zonacards.eventx.EventxMetrics;
import com.zonacards.eventx.Outcome;
import com.zonacards.eventx.OutboxStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The metrics an operator needs to answer "are events flowing?":
 *
 * <ul>
 *   <li>{@code zonacards_outbox_oldest_pending_seconds}: alarm when it grows; the relay cannot
 *       deliver;
 *   <li>{@code zonacards_outbox_pending} and {@code zonacards_outbox_parked};
 *   <li>{@code zonacards_outbox_published_total} and {@code ..._publish_failed_total};
 *   <li>{@code zonacards_messages_consumed_seconds} by queue, type and outcome
 *       (handled, duplicate, ignored, failed).
 * </ul>
 */
@Singleton
public class MicrometerEventxMetrics implements EventxMetrics {

  private final MeterRegistry registry;
  private final AtomicReference<OutboxStats> stats = new AtomicReference<>(OutboxStats.EMPTY);

  public MicrometerEventxMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("zonacards.outbox.pending", stats, s -> s.get().pending())
        .description("Messages written but not yet published")
        .register(registry);
    Gauge.builder("zonacards.outbox.oldest.pending.seconds", stats, s -> s.get().oldestPendingAge().toMillis() / 1000.0)
        .description("Age of the oldest unpublished message")
        .register(registry);
    Gauge.builder("zonacards.outbox.parked", stats, s -> s.get().parked())
        .description("Messages rejected by the broker and set aside")
        .register(registry);
  }

  @Override
  public void published(DestinationKind kind, String destination, int count) {
    if (count > 0) {
      registry.counter("zonacards.outbox.published", "kind", kind.name(), "destination", destination).increment(count);
    }
  }

  @Override
  public void publishFailed(DestinationKind kind, String destination, int count) {
    registry.counter("zonacards.outbox.publish.failed", "kind", kind.name(), "destination", destination).increment(count);
  }

  @Override
  public void parked(DestinationKind kind, String destination, int count) {
    registry.counter("zonacards.outbox.parked.total", "kind", kind.name(), "destination", destination).increment(count);
  }

  @Override
  public void outboxStats(OutboxStats current) {
    stats.set(current);
  }

  @Override
  public void consumed(String queue, String type, Outcome outcome, Duration elapsed) {
    Timer.builder("zonacards.messages.consumed")
        .description("Messages taken from a queue, by outcome")
        .tags("queue", queue, "type", type, "outcome", outcome.name().toLowerCase())
        .register(registry)
        .record(elapsed);
  }
}
