package com.zonacards.eventx;

import java.util.UUID;

/**
 * The correlation id of the work the current thread is doing. It is set at the two entry points
 * of a service (an HTTP request, a consumed message) and copied into every event produced while
 * handling it, so one id follows a request across both domains.
 */
public final class CorrelationContext {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private CorrelationContext() {}

  public static String current() {
    return CURRENT.get();
  }

  public static String currentOrNew() {
    String value = CURRENT.get();
    return value != null ? value : UUID.randomUUID().toString();
  }

  /** Sets the id and returns the previous one, to be given back to {@link #restore}. */
  public static String set(String correlationId) {
    String previous = CURRENT.get();
    if (correlationId == null || correlationId.isBlank()) {
      CURRENT.remove();
    } else {
      CURRENT.set(correlationId);
    }
    return previous;
  }

  public static void restore(String previous) {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }
}
