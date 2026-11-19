package com.zonacards.server;

import com.zonacards.eventx.EventxRuntime;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Starts the consumers and the relay when the application is up, and stops them in order when it
 * receives SIGTERM. Quarkus first stops accepting HTTP requests and waits for the ones in
 * flight; then this runs, while the datasource is still available.
 */
@ApplicationScoped
public class EventxLifecycle {

  @Inject Instance<EventxRuntime> runtime;
  @Inject MessagingConfig config;

  @ConfigProperty(name = "zonacards.mode", defaultValue = "serve")
  String mode;

  private volatile EventxRuntime started;

  void onStart(@Observes StartupEvent event) {
    // In migrate mode nothing is built: the task needs neither broker access nor queues.
    if (runtime.isResolvable() && !"migrate".equalsIgnoreCase(mode)) {
      started = runtime.get();
      started.start();
    }
  }

  void onStop(@Observes ShutdownEvent event) {
    if (started != null) {
      started.stop(config.shutdownTimeout());
    }
  }
}
