package com.zonacards.eventx;

import java.time.Duration;

/**
 * Deduplication of consumed messages. It is written in the same transaction as the handler's
 * effects, which is what makes "handled exactly once" true for the database even though
 * delivery is at-least-once.
 */
public interface Inbox {

  /** Records the message for the consumer. False when it had already been recorded. */
  boolean firstTime(String consumer, String messageId);

  int purge(Duration olderThan, int limit);
}
