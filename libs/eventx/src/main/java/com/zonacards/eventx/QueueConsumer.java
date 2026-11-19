package com.zonacards.eventx;

import com.zonacards.asyncx.Backoff;
import com.zonacards.asyncx.BoundedExecutor;
import com.zonacards.logx.Logx;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Consumes one queue: one poller, long polling, one virtual thread per message, and a hard limit
 * on messages in flight.
 *
 * <p>The limit is taken <b>before</b> asking the broker for messages. A consumer that receives
 * first and waits for capacity afterwards hoards messages that other tasks could be processing,
 * and their visibility timeout runs while they sit in memory. Size the limit against the
 * database pool: every in-flight message may hold a connection.
 *
 * <p>A message is deleted only after its handler committed. If the handler throws, the message
 * is left in the queue with a growing delay, and the queue's redrive policy moves it to the
 * dead-letter queue once it has been received too many times.
 */
public final class QueueConsumer {

  private static final Logx LOG = Logx.get(QueueConsumer.class);
  private static final Duration BEAT = Duration.ofSeconds(1);

  /**
   * @param queue logical queue name
   * @param concurrency maximum messages in flight in this task
   * @param waitTime long-poll time; 20 s is the maximum and the cheapest
   * @param visibilityTimeout how long a received message is hidden from other consumers; it is
   *     extended automatically while the handler runs
   * @param retryBackoff delay before the redelivery of a failed message, by receive count
   */
  public record Config(
      String queue, int concurrency, Duration waitTime, Duration visibilityTimeout, Backoff retryBackoff) {

    public static Config of(String queue, int concurrency) {
      return new Config(
          queue,
          concurrency,
          Duration.ofSeconds(20),
          Duration.ofSeconds(60),
          Backoff.exponential(Duration.ofSeconds(2), Duration.ofMinutes(5)));
    }
  }

  private final Config config;
  private final MessageReceiver receiver;
  private final MessageDispatcher dispatcher;
  private final EventxMetrics metrics;
  private final BoundedExecutor executor;

  private volatile boolean running;
  private volatile Thread poller;
  private volatile Instant lastBeat = Instant.now();

  public QueueConsumer(Config config, MessageReceiver receiver, MessageDispatcher dispatcher, EventxMetrics metrics) {
    this.config = config;
    this.receiver = receiver;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.executor = new BoundedExecutor("consumer-" + config.queue(), config.concurrency());
  }

  public String queue() {
    return config.queue();
  }

  public int inFlight() {
    return executor.inFlight();
  }

  public synchronized void start() {
    if (poller != null) {
      return;
    }
    running = true;
    poller = Thread.ofVirtual().name("poller-" + config.queue()).start(this::pollLoop);
  }

  /**
   * Graceful stop: cuts the long poll, gives back messages that were received but not started,
   * and waits for the ones in flight. Returns false when the timeout expired with work still
   * running; those messages become visible again when their visibility timeout runs out.
   */
  public boolean stop(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Thread current;
    synchronized (this) {
      current = poller;
      running = false;
    }
    if (current == null) {
      return true;
    }
    // The poller is a virtual thread: interrupting it closes the socket of the long poll.
    current.interrupt();
    try {
      current.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
      boolean drained = executor.awaitIdle(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
      return drained && !current.isAlive();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public boolean isAlive() {
    Thread current = poller;
    return current != null && current.isAlive();
  }

  /** Updated at least every second while the poller is alive, even when it is saturated. */
  public Instant lastBeat() {
    return lastBeat;
  }

  private void pollLoop() {
    int failures = 0;
    while (running) {
      int permits = 0;
      try {
        while (running && permits == 0) {
          lastBeat = Instant.now();
          permits = executor.tryAcquire(MessageSender.MAX_BATCH, BEAT);
        }
        if (!running) {
          break;
        }
        // The long poll is expected to keep this thread silent for up to waitTime.
        lastBeat = Instant.now().plus(config.waitTime());
        List<ReceivedMessage> messages =
            receiver.receive(config.queue(), permits, config.waitTime(), config.visibilityTimeout());
        failures = 0;
        executor.release(permits - messages.size());
        permits = 0;
        for (ReceivedMessage message : messages) {
          if (running) {
            executor.run(() -> process(message));
          } else {
            giveBack(message);
            executor.release(1);
          }
        }
      } catch (InterruptedException e) {
        break;
      } catch (RuntimeException e) {
        if (!running) {
          break;
        }
        failures++;
        LOG.withError(e).with("queue", config.queue()).with("consecutive_failures", failures).warn("receive failed");
        sleepAfterFailure(failures);
      } finally {
        executor.release(permits);
      }
    }
  }

  private void sleepAfterFailure(int failures) {
    long until = System.nanoTime() + Backoff.exponential(Duration.ofMillis(500), Duration.ofSeconds(30)).delay(failures).toNanos();
    try {
      while (running && System.nanoTime() < until) {
        lastBeat = Instant.now();
        Thread.sleep(Math.min(BEAT.toMillis(), Math.max(1, (until - System.nanoTime()) / 1_000_000)));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
  }

  private void process(ReceivedMessage message) {
    long started = System.nanoTime();
    String type = message.attributes().getOrDefault("type", "unknown");
    Thread extender = Thread.ofVirtual().name("visibility-" + config.queue()).start(() -> extendVisibility(message));
    Outcome outcome;
    Throwable failure = null;
    try {
      outcome = dispatcher.dispatch(message);
    } catch (Throwable t) {
      outcome = Outcome.FAILED;
      failure = t;
    } finally {
      extender.interrupt();
      try {
        extender.join(Duration.ofSeconds(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (failure == null) {
      try {
        receiver.delete(config.queue(), message);
      } catch (RuntimeException e) {
        // The effects are committed. The redelivery will be dropped by the inbox.
        LOG.withError(e).with("queue", config.queue()).with("message_id", message.messageId()).warn("delete failed");
      }
    } else {
      Duration retryIn = config.retryBackoff().delay(message.receiveCount());
      LOG.withError(failure)
          .with("queue", config.queue())
          .with("message_id", message.messageId())
          .with("type", type)
          .with("receive_count", message.receiveCount())
          .with("retry_in_ms", retryIn.toMillis())
          .warn("message handling failed; it will be redelivered");
      try {
        receiver.changeVisibility(config.queue(), message, retryIn);
      } catch (RuntimeException e) {
        // Without this the message simply comes back when its visibility timeout expires.
      }
    }
    metrics.consumed(config.queue(), type, outcome, Duration.ofNanos(System.nanoTime() - started));
  }

  /** Keeps a long-running message hidden, so a slow handler is not handed to a second task. */
  private void extendVisibility(ReceivedMessage message) {
    long half = Math.max(1, config.visibilityTimeout().toMillis() / 2);
    try {
      while (true) {
        Thread.sleep(half);
        receiver.changeVisibility(config.queue(), message, config.visibilityTimeout());
      }
    } catch (InterruptedException e) {
      // The handler finished.
    } catch (RuntimeException e) {
      // Best effort: worst case another task receives it and the handler's own idempotency decides.
    }
  }

  private void giveBack(ReceivedMessage message) {
    try {
      receiver.changeVisibility(config.queue(), message, Duration.ZERO);
    } catch (RuntimeException e) {
      // It comes back by itself when the visibility timeout expires.
    }
  }
}
