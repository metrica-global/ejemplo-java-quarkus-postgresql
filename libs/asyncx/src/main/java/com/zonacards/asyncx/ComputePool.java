package com.zonacards.asyncx;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed pool of platform threads for CPU-bound work.
 *
 * <p>Virtual threads are for waiting, not for computing: they are not time-sliced, so a long
 * computation on a virtual thread keeps its carrier until it finishes. On a task with one vCPU
 * that would freeze every other virtual thread of the process (the queue pollers, the outbox
 * relay, the heartbeats behind the health check) for as long as the computation lasts. Work
 * submitted here runs on operating-system threads instead, which the kernel does preempt; the
 * calling virtual thread simply parks until the result is ready.
 */
public final class ComputePool implements AutoCloseable {

  private final ExecutorService executor;
  private final int threads;

  /** @param threads number of threads; zero or less means one per available processor */
  public ComputePool(String name, int threads) {
    this.threads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
    AtomicInteger sequence = new AtomicInteger();
    this.executor =
        Executors.newFixedThreadPool(
            this.threads,
            runnable -> {
              Thread thread = new Thread(runnable, name + "-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  public int threads() {
    return threads;
  }

  /** Runs the work on the pool and waits for it. Runtime exceptions of the work are rethrown as they are. */
  public <T> T call(Callable<T> work) {
    Future<T> future = executor.submit(work);
    try {
      return future.get();
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for a computation", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(cause);
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
