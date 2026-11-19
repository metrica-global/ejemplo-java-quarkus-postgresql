package com.zonacards.asyncx;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with a cap and jitter. Jitter matters: without it, every task that failed
 * at the same moment retries at the same moment.
 *
 * @param jitter fraction of the delay that is randomised, between 0 (none) and 1
 */
public record Backoff(Duration base, Duration max, double factor, double jitter) {

  public Backoff {
    if (base == null || base.isNegative() || base.isZero()) {
      throw new IllegalArgumentException("base must be positive");
    }
    if (max == null || max.compareTo(base) < 0) {
      throw new IllegalArgumentException("max must be >= base");
    }
    if (factor < 1) {
      throw new IllegalArgumentException("factor must be >= 1");
    }
    if (jitter < 0 || jitter > 1) {
      throw new IllegalArgumentException("jitter must be between 0 and 1");
    }
  }

  public static Backoff exponential(Duration base, Duration max) {
    return new Backoff(base, max, 2.0, 0.2);
  }

  /** Delay before the retry that follows the given attempt (1 = first failure). */
  public Duration delay(int attempt) {
    return delay(attempt, ThreadLocalRandom.current().nextDouble());
  }

  /** Same as {@link #delay(int)} with the random sample supplied, for deterministic tests. */
  public Duration delay(int attempt, double random) {
    int exponent = Math.max(0, Math.min(attempt - 1, 62));
    double raw = base.toMillis() * Math.pow(factor, exponent);
    double capped = Math.min(raw, max.toMillis());
    double spread = capped * jitter;
    double value = capped - spread + (2 * spread * random);
    return Duration.ofMillis(Math.max(1, Math.min(Math.round(value), max.toMillis())));
  }
}
