package com.zonacards.asyncx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncxTest {

  @Test
  void backoffGrowsIsCappedAndJitters() {
    Backoff backoff = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(2), 2.0, 0.5);
    assertEquals(Duration.ofMillis(100), backoff.delay(1, 0.5));
    assertEquals(Duration.ofMillis(200), backoff.delay(2, 0.5));
    assertEquals(Duration.ofMillis(400), backoff.delay(3, 0.5));
    assertEquals(Duration.ofMillis(2000), backoff.delay(30, 0.5));
    assertEquals(Duration.ofMillis(50), backoff.delay(1, 0.0));
    assertEquals(Duration.ofMillis(150), backoff.delay(1, 1.0));
    assertEquals(Duration.ofMillis(2000), backoff.delay(30, 1.0), "jitter never exceeds the cap");
    assertThrows(IllegalArgumentException.class, () -> new Backoff(Duration.ZERO, Duration.ofSeconds(1), 2, 0));
  }

  @Test
  void retryStopsOnSuccessOnNonRetryableAndOnTheAttemptLimit() throws Exception {
    Backoff fast = new Backoff(Duration.ofMillis(1), Duration.ofMillis(2), 2, 0);
    AtomicInteger calls = new AtomicInteger();
    String result =
        Retry.call(
            5,
            fast,
            e -> e instanceof IOException,
            () -> {
              if (calls.incrementAndGet() < 3) {
                throw new IOException("transient");
              }
              return "ok";
            });
    assertEquals("ok", result);
    assertEquals(3, calls.get());

    calls.set(0);
    assertThrows(
        IllegalStateException.class,
        () ->
            Retry.call(
                5,
                fast,
                e -> e instanceof IOException,
                () -> {
                  calls.incrementAndGet();
                  throw new IllegalStateException("permanent");
                }));
    assertEquals(1, calls.get());

    calls.set(0);
    assertThrows(
        IOException.class,
        () ->
            Retry.call(
                3,
                fast,
                e -> true,
                () -> {
                  calls.incrementAndGet();
                  throw new IOException("always");
                }));
    assertEquals(3, calls.get());
  }

  @Test
  void boundedExecutorNeverExceedsItsLimit() throws Exception {
    BoundedExecutor executor = new BoundedExecutor("test", 3);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    AtomicInteger done = new AtomicInteger();
    int submitted = 0;
    while (submitted < 40) {
      int taken = executor.acquire(10);
      assertTrue(taken >= 1 && taken <= 3);
      for (int i = 0; i < taken; i++) {
        submitted++;
        executor.run(
            () -> {
              int now = running.incrementAndGet();
              peak.accumulateAndGet(now, Math::max);
              try {
                Thread.sleep(5);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              running.decrementAndGet();
              done.incrementAndGet();
            });
      }
    }
    assertTrue(executor.awaitIdle(Duration.ofSeconds(10)));
    assertEquals(submitted, done.get());
    assertTrue(peak.get() <= 3, "peak was " + peak.get());
    assertEquals(0, executor.inFlight());
  }

  @Test
  void awaitIdleTimesOutWhileWorkIsInFlight() throws Exception {
    BoundedExecutor executor = new BoundedExecutor("test", 1);
    CountDownLatch release = new CountDownLatch(1);
    executor.acquire(1);
    executor.run(
        () -> {
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertFalse(executor.awaitIdle(Duration.ofMillis(50)));
    release.countDown();
    assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
  }

  @Test
  void theComputePoolRunsOnPlatformThreadsAndKeepsVirtualThreadsResponsive() throws Exception {
    try (ComputePool pool = new ComputePool("cpu", 2)) {
      assertEquals(2, pool.threads());
      assertFalse(pool.call(() -> Thread.currentThread().isVirtual()), "CPU work must not run on a virtual thread");
      assertTrue(pool.call(() -> Thread.currentThread().getName()).startsWith("cpu-"));
      assertThrows(IllegalStateException.class, () -> pool.call(() -> { throw new IllegalStateException("as thrown"); }));

      // A virtual thread that waits for a long computation leaves the others free to run.
      AtomicInteger ticks = new AtomicInteger();
      CountDownLatch release = new CountDownLatch(1);
      Thread waiting = Thread.ofVirtual().start(() -> pool.call(() -> { release.await(); return 1; }));
      Thread ticker =
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (int i = 0; i < 20; i++) {
                      ticks.incrementAndGet();
                      try {
                        Thread.sleep(2);
                      } catch (InterruptedException e) {
                        return;
                      }
                    }
                  });
      ticker.join(Duration.ofSeconds(5));
      assertEquals(20, ticks.get());
      release.countDown();
      waiting.join(Duration.ofSeconds(5));
    }
    assertTrue(new ComputePool("default", 0).threads() >= 1);
  }
}
