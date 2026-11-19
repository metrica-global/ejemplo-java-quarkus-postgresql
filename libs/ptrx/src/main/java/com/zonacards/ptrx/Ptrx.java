package com.zonacards.ptrx;

/**
 * Helpers for optional values. The Go original exists because Go needs pointers to express
 * "absent"; Java already has {@code null}, {@link java.util.Optional} and records, so only the
 * few helpers that make partial updates readable are kept.
 */
public final class Ptrx {

  private Ptrx() {}

  /** The value, or the fallback when the value is null. Typical use: partial updates. */
  public static <T> T valueOr(T value, T fallback) {
    return value != null ? value : fallback;
  }

  /** The first non-null argument, or null. */
  @SafeVarargs
  public static <T> T coalesce(T... values) {
    if (values != null) {
      for (T value : values) {
        if (value != null) {
          return value;
        }
      }
    }
    return null;
  }

  /** The first argument that is neither null nor blank, or null. */
  public static String firstNonBlank(String... values) {
    if (values != null) {
      for (String value : values) {
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
    }
    return null;
  }

  /** Null when the text is null or blank, otherwise the trimmed text. */
  public static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
