package com.zonacards.asyncx;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs tasks on virtual threads with a hard limit on how many are in flight.
 *
 * <p>Virtual threads are cheap, but what they touch is not: every in-flight task may hold a
 * database connection. The caller takes permits <b>before</b> it asks for work, so it never
 * holds work it cannot start. That is the back-pressure of the queue consumers.
 */
public final class BoundedExecutor {

  private final String name;
  private final int limit;
  private final Semaphore permits;
  private final AtomicLong sequence = new AtomicLong();

  public BoundedExecutor(String name, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be >= 1");
    }
    this.name = name;
    this.limit = limit;
    this.permits = new Semaphore(limit, true);
  }

  /** Blocks until at least one permit is free, then takes up to {@code max}. Returns how many. */
  public int acquire(int max) throws InterruptedException {
    permits.acquire();
    int taken = 1;
    while (taken < max && permits.tryAcquire()) {
      taken++;
    }
    return taken;
  }

  /** Like {@link #acquire(int)} but gives up after the timeout and returns 0. */
  public int tryAcquire(int max, Duration timeout) throws InterruptedException {
    if (!permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      return 0;
    }
    int taken = 1;
    while (taken < max && permits.tryAcquire()) {
      taken++;
    }
    return taken;
  }

  public void release(int count) {
    if (count > 0) {
      permits.release(count);
    }
  }

  /** Starts the task on a virtual thread. The caller must hold a permit; it is released at the end. */
  public void run(Runnable task) {
    Thread.ofVirtual()
        .name(name + "-" + sequence.incrementAndGet())
        .start(
            () -> {
              try {
                task.run();
              } finally {
                permits.release();
              }
            });
  }

  public int inFlight() {
    return limit - permits.availablePermits();
  }

  public int limit() {
    return limit;
  }

  /** Waits until nothing is in flight. Returns false if the timeout expires first. */
  public boolean awaitIdle(Duration timeout) throws InterruptedException {
    if (permits.tryAcquire(limit, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      permits.release(limit);
      return true;
    }
    return false;
  }
}
