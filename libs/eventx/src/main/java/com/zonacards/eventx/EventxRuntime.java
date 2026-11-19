package com.zonacards.eventx;

import com.zonacards.logx.Logx;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The background loops of a service: its consumers and its outbox relay. A service has exactly
 * one; its container builds it and the application lifecycle starts and stops it.
 *
 * <p>Shutdown order matters. Consumers stop first and drain their in-flight messages; the relay
 * stops last so the events those messages produced still get published before the task exits.
 */
public final class EventxRuntime {

  private static final Logx LOG = Logx.get(EventxRuntime.class);

  private final OutboxRelay relay;
  private final List<QueueConsumer> consumers;
  private volatile boolean started;

  /** @param relay null when this process does not publish (relay disabled by configuration) */
  public EventxRuntime(OutboxRelay relay, List<QueueConsumer> consumers) {
    this.relay = relay;
    this.consumers = List.copyOf(consumers);
  }

  public synchronized void start() {
    if (started) {
      return;
    }
    started = true;
    if (relay != null) {
      relay.start();
    }
    for (QueueConsumer consumer : consumers) {
      consumer.start();
    }
    LOG.with("relay", relay != null).with("queues", consumers.stream().map(QueueConsumer::queue).toList()).info("eventx started");
  }

  /** Stops everything within the timeout: consumers get most of it, the relay the rest. */
  public synchronized void stop(Duration timeout) {
    if (!started) {
      return;
    }
    started = false;
    long deadline = System.nanoTime() + timeout.toNanos();
    Duration forConsumers = Duration.ofNanos(timeout.toNanos() * 3 / 4);
    List<Thread> stoppers = new ArrayList<>();
    for (QueueConsumer consumer : consumers) {
      stoppers.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    if (!consumer.stop(forConsumers)) {
                      LOG.with("queue", consumer.queue())
                          .with("in_flight", consumer.inFlight())
                          .warn("consumer did not drain in time; its messages will be redelivered");
                    }
                  }));
    }
    for (Thread stopper : stoppers) {
      try {
        stopper.join(forConsumers.plusSeconds(1));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (relay != null) {
      relay.stop(Duration.ofNanos(Math.max(Duration.ofSeconds(1).toNanos(), deadline - System.nanoTime())));
    }
    LOG.info("eventx stopped");
  }

  public boolean started() {
    return started;
  }

  /** The last heartbeat of every loop, by name. A loop whose thread died reports the epoch. */
  public Map<String, Instant> heartbeats() {
    Map<String, Instant> beats = new LinkedHashMap<>();
    if (relay != null) {
      beats.put("outbox-relay", relay.isAlive() ? relay.lastBeat() : Instant.EPOCH);
    }
    for (QueueConsumer consumer : consumers) {
      beats.put("consumer-" + consumer.queue(), consumer.isAlive() ? consumer.lastBeat() : Instant.EPOCH);
    }
    return beats;
  }
}
