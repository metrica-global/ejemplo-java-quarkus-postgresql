package com.zonacards.server;

import com.zonacards.eventx.EventxRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness of the background loops. A task whose HTTP endpoint answers but whose consumer died
 * is a zombie: it looks healthy and processes nothing. Each loop reports a heartbeat at least
 * every second, even while it is saturated with work, so "silent" really means "dead" and the
 * orchestrator should replace the task.
 *
 * <p>This is deliberately a liveness check and not a readiness check that includes the
 * database: on ECS a failing health check replaces the task, and a database blip must not
 * restart every task at once.
 */
@Liveness
@ApplicationScoped
public class EventxLivenessCheck implements HealthCheck {

  @Inject Instance<EventxRuntime> runtime;
  @Inject MessagingConfig config;

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response = HealthCheckResponse.named("eventx-loops");
    if (!runtime.isResolvable() || !runtime.get().started()) {
      return response.up().withData("loops", "not started").build();
    }
    Instant now = Instant.now();
    boolean healthy = true;
    for (Map.Entry<String, Instant> beat : runtime.get().heartbeats().entrySet()) {
      Duration silence = Duration.between(beat.getValue(), now);
      boolean alive = silence.compareTo(config.healthMaxSilence()) <= 0;
      healthy &= alive;
      response.withData(beat.getKey(), alive ? "alive" : "silent for " + silence.toSeconds() + " s");
    }
    return response.status(healthy).build();
  }
}
