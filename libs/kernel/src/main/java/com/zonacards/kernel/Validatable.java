package com.zonacards.kernel;

/**
 * A request that knows how to validate its own shape. Validation is explicit code, not
 * annotations: it stays visible, testable, and it runs for every caller (HTTP, events, jobs).
 *
 * <p>Implementations must be deterministic and free of I/O. They check shape and supported
 * values; authority, lifecycle and uniqueness belong to the service and to the database.
 */
public interface Validatable {

  /** Throws a VALIDATION {@link com.zonacards.errx.Errx} when the request is not acceptable. */
  void validate();
}
