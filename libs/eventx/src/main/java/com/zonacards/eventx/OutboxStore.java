package com.zonacards.eventx;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence port of the outbox. Every method runs inside the caller's unit of work. */
public interface OutboxStore {

  void add(Collection<OutboxMessage> messages);

  /**
   * Takes up to {@code limit} deliverable messages and leases them to the caller: until the
   * lease expires no other relay will take them. If the caller crashes, the lease runs out and
   * the messages become deliverable again; that is how a crash turns into a duplicate instead of
   * a loss. Concurrent callers never get the same row.
   */
  List<OutboxMessage> claim(int limit, Duration lease);

  void markPublished(Collection<UUID> ids);

  /** Releases the lease and makes the message deliverable again after the delay. */
  void reschedule(UUID id, String error, Duration delay);

  /** Sets the message aside: it will not be retried until an operator releases it. */
  void park(UUID id, String error);

  OutboxStats stats();

  /** Deletes published rows older than the retention. Returns how many were deleted. */
  int purgePublished(Duration olderThan, int limit);
}
