package com.zonacards.eventx;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Outcome of sending one batch. Brokers accept batches partially, so the result is per message.
 *
 * @param sent ids the broker confirmed
 * @param failed ids it did not, with the reason
 */
public record SendResult(Set<UUID> sent, Map<UUID, Failure> failed) {

  /**
   * @param permanent true when retrying cannot help (the broker says the message itself is
   *     invalid); the relay parks it. False for throttling, timeouts and outages: retried forever.
   */
  public record Failure(String error, boolean permanent) {}

  public static SendResult allFailed(Collection<OutboxMessage> batch, String error) {
    Map<UUID, Failure> failed = new HashMap<>();
    for (OutboxMessage message : batch) {
      failed.put(message.id(), new Failure(error, false));
    }
    return new SendResult(Set.of(), failed);
  }
}
