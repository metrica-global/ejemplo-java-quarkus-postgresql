package com.zonacards.server;

import com.zonacards.logx.Logx;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Starts the modules this deployment serves when the application is up, and stops them when it
 * receives SIGTERM. Quarkus first stops accepting HTTP requests and waits for the ones in
 * flight; then this runs, while the datasources are still available.
 *
 * <p>A module is assembled here, at startup, and not on its first request: if the deployment
 * was not given what the module needs, the task must fail to start, which is what makes the
 * orchestrator stop a rollout and roll back.
 */
@ApplicationScoped
public class ModuleLifecycle {

  /** What the liveness check needs to tell "still starting" from "should be running". */
  public enum State {
    STARTING,
    RUNNING,
    STOPPING
  }

  private static final Logx LOG = Logx.get(ModuleLifecycle.class);

  @Inject ActiveModules modules;
  @Inject MessagingConfig config;

  @ConfigProperty(name = "zonacards.mode", defaultValue = "serve")
  String mode;

  private final List<AppModule> started = new CopyOnWriteArrayList<>();
  private volatile State state = State.STARTING;

  void onStart(@Observes StartupEvent event) {
    // In migrate mode nothing is assembled: the task needs neither the broker nor the queues.
    if (migrating()) {
      return;
    }
    modules.validate();
    try {
      for (AppModule module : modules.served()) {
        module.start();
        started.add(module);
      }
    } catch (RuntimeException e) {
      stopAll(Duration.ofSeconds(5));
      throw e;
    }
    state = State.RUNNING;
    LOG.with("modules", started.stream().map(AppModule::name).toList())
        .with("not_served", modules.all().stream().filter(m -> !started.contains(m)).map(AppModule::name).toList())
        .info("modules started");
  }

  void onStop(@Observes ShutdownEvent event) {
    state = State.STOPPING;
    stopAll(config.shutdownTimeout());
  }

  public State state() {
    return state;
  }

  public boolean migrating() {
    return "migrate".equalsIgnoreCase(mode);
  }

  /** The modules that were started, for the liveness check. */
  public List<AppModule> started() {
    return started;
  }

  /**
   * All modules stop at the same time and share one deadline. One after the other, two modules
   * would need twice the time the orchestrator gives a task before killing it.
   */
  private void stopAll(Duration timeout) {
    List<Thread> stopping = new ArrayList<>();
    for (AppModule module : started) {
      stopping.add(Thread.ofVirtual().name("stop-" + module.name()).start(() -> module.stop(timeout)));
    }
    long deadline = System.nanoTime() + timeout.toNanos() + Duration.ofSeconds(2).toNanos();
    for (Thread thread : stopping) {
      try {
        thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
