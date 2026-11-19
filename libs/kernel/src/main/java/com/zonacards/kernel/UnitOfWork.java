package com.zonacards.kernel;

import java.util.function.Supplier;

/**
 * The transaction boundary of a use case. Services call it explicitly instead of carrying a
 * framework annotation, so the boundary is visible in the code that owns the business rule.
 *
 * <p>Semantics are join-or-begin: if a transaction is already active the work joins it, otherwise
 * a new one is started and committed when the work returns. Any exception rolls it back.
 * Repositories and the outbox written inside the same call commit or fail together.
 */
public interface UnitOfWork {

  <T> T inTx(Supplier<T> work);

  default void runInTx(Runnable work) {
    inTx(
        () -> {
          work.run();
          return null;
        });
  }

  /** Runs the work inline with no transaction. For unit tests that use in-memory fakes. */
  static UnitOfWork direct() {
    return new UnitOfWork() {
      @Override
      public <T> T inTx(Supplier<T> work) {
        return work.get();
      }
    };
  }
}
