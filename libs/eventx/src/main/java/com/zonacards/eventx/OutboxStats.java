package com.zonacards.eventx;

import java.time.Duration;

/**
 * Health of the outbox. {@code oldestPendingAge} is the number to alarm on: it grows when the
 * relay cannot deliver, whatever the reason.
 */
public record OutboxStats(long pending, Duration oldestPendingAge, long parked) {

  public static final OutboxStats EMPTY = new OutboxStats(0, Duration.ZERO, 0);
}
