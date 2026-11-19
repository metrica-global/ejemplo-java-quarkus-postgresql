package com.zonacards.asyncx;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/** Retries a call a bounded number of times, sleeping with backoff between attempts. */
public final class Retry {

  private Retry() {}

  /**
   * @param maxAttempts total attempts, including the first one
   * @param retryable which failures are worth another attempt; anything else is rethrown at once
   */
  public static <T> T call(int maxAttempts, Backoff backoff, Predicate<Throwable> retryable, Callable<T> work)
      throws Exception {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    int attempt = 1;
    while (true) {
      try {
        return work.call();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw e;
      } catch (Exception e) {
        if (attempt >= maxAttempts || !retryable.test(e)) {
          throw e;
        }
        Thread.sleep(backoff.delay(attempt));
        attempt++;
      }
    }
  }
}
