package com.zonacards.eventx;

import java.time.Duration;

/** Port to the metrics system. Every method has a no-op default. */
public interface EventxMetrics {

  EventxMetrics NONE = new EventxMetrics() {};

  default void published(DestinationKind kind, String destination, int count) {}

  default void publishFailed(DestinationKind kind, String destination, int count) {}

  default void parked(DestinationKind kind, String destination, int count) {}

  default void outboxStats(OutboxStats stats) {}

  default void consumed(String queue, String type, Outcome outcome, Duration elapsed) {}
}
