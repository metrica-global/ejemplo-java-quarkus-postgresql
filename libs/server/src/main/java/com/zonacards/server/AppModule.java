package com.zonacards.server;

import com.zonacards.eventx.EventxRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A module of the application as the platform sees it: a name, the API paths it owns and its
 * background loops (outbox relay and queue consumers).
 *
 * <p>The application always <em>contains</em> every module; a deployment <em>serves</em> only
 * the ones listed in {@code ZC_MODULES}. A module that is not served is never assembled, so its
 * database credentials, queues and keys do not have to exist in that deployment. That is what
 * lets the same image run as one service with everything, or as several services with one
 * module each.
 */
public final class AppModule {

  private final String name;
  private final String apiPath;
  private final Supplier<EventxRuntime> assembly;
  private volatile EventxRuntime runtime;

  private AppModule(String name, String apiPath, Supplier<EventxRuntime> assembly) {
    this.name = Objects.requireNonNull(name);
    this.apiPath = Objects.requireNonNull(apiPath);
    this.assembly = Objects.requireNonNull(assembly);
  }

  /**
   * @param name the module's name: also its datasource, its schema and the prefix of its roles
   * @param apiPath the path prefix of its REST API, for example {@code /api/v1/lotes}
   * @param assembly builds the module's object graph, verifies that this deployment was given
   *     what the module needs, and returns its loops. Called once, and only if the module is
   *     served.
   */
  public static AppModule of(String name, String apiPath, Supplier<EventxRuntime> assembly) {
    return new AppModule(name, apiPath, assembly);
  }

  public String name() {
    return name;
  }

  public String apiPath() {
    return apiPath;
  }

  /** True if the path belongs to this module's API. */
  public boolean owns(String path) {
    return path.equals(apiPath) || path.startsWith(apiPath + "/");
  }

  synchronized void start() {
    if (runtime == null) {
      runtime = assembly.get();
    }
    runtime.start();
  }

  synchronized void stop(Duration timeout) {
    if (runtime != null) {
      runtime.stop(timeout);
    }
  }

  boolean running() {
    EventxRuntime current = runtime;
    return current != null && current.started();
  }

  /** Heartbeats of this module's loops, named {@code <module>/<loop>}. */
  Map<String, Instant> heartbeats() {
    Map<String, Instant> beats = new LinkedHashMap<>();
    EventxRuntime current = runtime;
    if (current != null) {
      current.heartbeats().forEach((loop, beat) -> beats.put(name + "/" + loop, beat));
    }
    return beats;
  }
}
