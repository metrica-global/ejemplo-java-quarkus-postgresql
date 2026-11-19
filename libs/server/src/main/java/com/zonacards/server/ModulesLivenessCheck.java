package com.zonacards.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness of the background loops of every module this deployment serves. A task whose HTTP
 * endpoint answers but whose consumer died is a zombie: it looks healthy and processes nothing.
 * Each loop reports a heartbeat at least every second, even while it is saturated with work, so
 * "silent" really means "dead" and the orchestrator should replace the task.
 *
 * <p>This is deliberately a liveness check and not a readiness check that includes the
 * database: on ECS a failing health check replaces the task, and a database blip must not
 * restart every task at once.
 */
@Liveness
@ApplicationScoped
public class ModulesLivenessCheck implements HealthCheck {

  @Inject ModuleLifecycle lifecycle;
  @Inject MessagingConfig config;

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response = HealthCheckResponse.named("eventx-loops");
    if (lifecycle.migrating() || lifecycle.state() != ModuleLifecycle.State.RUNNING) {
      return response.up().withData("loops", lifecycle.state().name().toLowerCase()).build();
    }
    Instant now = Instant.now();
    boolean healthy = true;
    for (AppModule module : lifecycle.started()) {
      if (!module.running()) {
        healthy = false;
        response.withData(module.name(), "not running");
        continue;
      }
      for (Map.Entry<String, Instant> beat : module.heartbeats().entrySet()) {
        Duration silence = Duration.between(beat.getValue(), now);
        boolean alive = silence.compareTo(config.healthMaxSilence()) <= 0;
        healthy &= alive;
        response.withData(beat.getKey(), alive ? "alive" : "silent for " + silence.toSeconds() + " s");
      }
    }
    return response.status(healthy).build();
  }
}
