package com.zonacards.eventx;

import com.zonacards.asyncx.Backoff;
import com.zonacards.kernel.UnitOfWork;
import com.zonacards.logx.Logx;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Moves messages from the outbox table to the broker. Every task of a service runs one; they
 * cooperate through the database, with no leader election:
 *
 * <ol>
 *   <li>claim a batch with a lease, in a short transaction ({@code FOR UPDATE SKIP LOCKED});
 *   <li>publish outside any transaction, so a slow broker never holds a database lock;
 *   <li>mark what the broker confirmed; reschedule what it did not, with backoff.
 * </ol>
 *
 * A crash between 2 and 3 republishes after the lease expires: duplicates are possible, losses
 * are not. Transient failures are retried forever (the alarm is the age of the oldest pending
 * message); only a message the broker rejects as invalid is parked.
 */
public final class OutboxRelay {

  private static final Logx LOG = Logx.get(OutboxRelay.class);

  /**
   * @param batchSize rows claimed per round
   * @param lease how long a claim lasts; must comfortably exceed the time to publish a batch
   * @param pollInterval sleep when the outbox is empty; the floor of the delivery latency
   * @param retention how long published rows are kept (for diagnosis) before being deleted
   */
  public record Config(
      int batchSize,
      Duration lease,
      Duration pollInterval,
      Backoff retryBackoff,
      Duration retention,
      Duration inboxRetention,
      Duration housekeepingInterval) {

    public static Config defaults() {
      return new Config(
          50,
          Duration.ofSeconds(30),
          Duration.ofMillis(250),
          Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(2)),
          Duration.ofDays(3),
          Duration.ofDays(14),
          Duration.ofSeconds(15));
    }
  }

  private record Destination(DestinationKind kind, String name) {}

  private final OutboxStore store;
  private final MessageSender sender;
  private final UnitOfWork uow;
  private final Inbox inbox;
  private final EventxMetrics metrics;
  private final Config config;

  private final CountDownLatch stopSignal = new CountDownLatch(1);
  private volatile boolean running;
  private volatile Thread thread;
  private volatile Instant lastBeat = Instant.now();
  private volatile Instant lastHousekeeping = Instant.EPOCH;

  /** @param inbox optional; when given, the relay also purges old deduplication records */
  public OutboxRelay(
      OutboxStore store, MessageSender sender, UnitOfWork uow, Inbox inbox, EventxMetrics metrics, Config config) {
    this.store = store;
    this.sender = sender;
    this.uow = uow;
    this.inbox = inbox;
    this.metrics = metrics;
    this.config = config;
  }

  public synchronized void start() {
    if (thread != null) {
      return;
    }
    running = true;
    thread = Thread.ofVirtual().name("outbox-relay").start(this::loop);
  }

  /**
   * Stops the loop, then publishes whatever is still pending until the outbox is empty or the
   * timeout expires. Anything left is published by another task, or by this service's next one.
   */
  public boolean stop(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Thread current;
    synchronized (this) {
      current = thread;
      running = false;
      stopSignal.countDown();
    }
    if (current == null) {
      return true;
    }
    try {
      current.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
      while (!current.isAlive() && System.nanoTime() < deadline) {
        if (runOnce() == 0) {
          return true;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.withError(e).warn("outbox flush on shutdown failed; pending messages stay in the outbox");
    }
    return false;
  }

  public boolean isAlive() {
    Thread current = thread;
    return current != null && current.isAlive();
  }

  public Instant lastBeat() {
    return lastBeat;
  }

  private void loop() {
    int failures = 0;
    while (running) {
      lastBeat = Instant.now();
      try {
        int claimed = runOnce();
        failures = 0;
        housekeepingIfDue();
        if (claimed < config.batchSize()) {
          await(config.pollInterval());
        }
      } catch (RuntimeException e) {
        failures++;
        LOG.withError(e).with("consecutive_failures", failures).warn("outbox relay round failed");
        await(Backoff.exponential(Duration.ofMillis(500), Duration.ofSeconds(30)).delay(failures));
      }
    }
  }

  private void await(Duration duration) {
    try {
      if (stopSignal.await(duration.toMillis(), TimeUnit.MILLISECONDS)) {
        running = false;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
  }

  /** One round: claim, publish, settle. Returns how many messages were claimed. */
  public int runOnce() {
    List<OutboxMessage> claimed = uow.inTx(() -> store.claim(config.batchSize(), config.lease()));
    if (claimed.isEmpty()) {
      return 0;
    }
    Map<Destination, List<OutboxMessage>> byDestination = new LinkedHashMap<>();
    for (OutboxMessage message : claimed) {
      byDestination
          .computeIfAbsent(new Destination(message.kind(), message.destination()), d -> new ArrayList<>())
          .add(message);
    }
    for (Map.Entry<Destination, List<OutboxMessage>> entry : byDestination.entrySet()) {
      List<OutboxMessage> messages = entry.getValue();
      for (int from = 0; from < messages.size(); from += MessageSender.MAX_BATCH) {
        List<OutboxMessage> batch = messages.subList(from, Math.min(from + MessageSender.MAX_BATCH, messages.size()));
        settle(entry.getKey(), batch, send(entry.getKey(), batch));
      }
    }
    return claimed.size();
  }

  private SendResult send(Destination destination, List<OutboxMessage> batch) {
    try {
      return sender.send(destination.kind(), destination.name(), batch);
    } catch (RuntimeException e) {
      return SendResult.allFailed(batch, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private void settle(Destination destination, List<OutboxMessage> batch, SendResult result) {
    int[] counts = new int[2];
    uow.runInTx(
        () -> {
          store.markPublished(result.sent());
          for (OutboxMessage message : batch) {
            SendResult.Failure failure = result.failed().get(message.id());
            if (failure == null) {
              continue;
            }
            if (failure.permanent()) {
              store.park(message.id(), failure.error());
              counts[1]++;
            } else {
              store.reschedule(message.id(), failure.error(), config.retryBackoff().delay(message.attempts()));
              counts[0]++;
            }
          }
        });
    metrics.published(destination.kind(), destination.name(), result.sent().size());
    if (counts[0] > 0) {
      metrics.publishFailed(destination.kind(), destination.name(), counts[0]);
      LOG.with("destination", destination.name())
          .with("count", counts[0])
          .with("error", result.failed().values().iterator().next().error())
          .warn("messages not published; they will be retried");
    }
    if (counts[1] > 0) {
      metrics.parked(destination.kind(), destination.name(), counts[1]);
      LOG.with("destination", destination.name())
          .with("count", counts[1])
          .error("messages rejected by the broker and parked; see the outbox runbook");
    }
  }

  private void housekeepingIfDue() {
    Instant now = Instant.now();
    if (Duration.between(lastHousekeeping, now).compareTo(config.housekeepingInterval()) < 0) {
      return;
    }
    lastHousekeeping = now;
    try {
      metrics.outboxStats(uow.inTx(store::stats));
      uow.runInTx(() -> store.purgePublished(config.retention(), 1_000));
      if (inbox != null) {
        uow.runInTx(() -> inbox.purge(config.inboxRetention(), 1_000));
      }
    } catch (RuntimeException e) {
      LOG.withError(e).warn("outbox housekeeping failed");
    }
  }
}
