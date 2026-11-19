package com.zonacards.server;

import java.util.ArrayList;
import java.util.List;

/**
 * What a module checks before it starts: that this deployment was given everything the module
 * needs. A task with a missing queue URL or a missing permission would otherwise start, answer
 * its health check and process nothing. Failing the start instead makes the orchestrator's
 * deployment fail and roll back.
 *
 * <p>Every problem is collected, so one failed start reports all of them.
 */
public final class Preflight {

  private final String module;
  private final List<String> problems = new ArrayList<>();

  private Preflight(String module) {
    this.module = module;
  }

  public static Preflight of(String module) {
    return new Preflight(module);
  }

  public Preflight require(String what, boolean present) {
    if (!present) {
      problems.add(what + " is not configured");
    }
    return this;
  }

  /** Runs a probe (resolve an ARN, read a queue's attributes); a failure is recorded, not thrown. */
  public Preflight check(String what, Runnable probe) {
    try {
      probe.run();
    } catch (RuntimeException e) {
      problems.add(what + ": " + e.getMessage());
    }
    return this;
  }

  public void verify() {
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "module '" + module + "' cannot be served by this deployment: " + String.join("; ", problems));
    }
  }
}
