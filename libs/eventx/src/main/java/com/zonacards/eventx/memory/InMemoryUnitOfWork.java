package com.zonacards.eventx.memory;

import com.zonacards.kernel.UnitOfWork;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * A unit of work for the in-memory adapters. It gives them the one property of a database
 * transaction the event machinery depends on: when the work throws, what the in-memory inbox and
 * outbox recorded during it is undone. Without that, a failed handler would look "already
 * handled" on redelivery, which is exactly the bug a real transaction prevents.
 */
public final class InMemoryUnitOfWork implements UnitOfWork {

  private static final ThreadLocal<Deque<Runnable>> UNDO = new ThreadLocal<>();

  /** Called by the in-memory adapters to register how to undo a write. No-op outside a unit of work. */
  static void onRollback(Runnable undo) {
    Deque<Runnable> log = UNDO.get();
    if (log != null) {
      log.push(undo);
    }
  }

  @Override
  public <T> T inTx(Supplier<T> work) {
    if (UNDO.get() != null) {
      return work.get();
    }
    Deque<Runnable> log = new ArrayDeque<>();
    UNDO.set(log);
    try {
      return work.get();
    } catch (RuntimeException | Error e) {
      while (!log.isEmpty()) {
        log.pop().run();
      }
      throw e;
    } finally {
      UNDO.remove();
    }
  }
}
