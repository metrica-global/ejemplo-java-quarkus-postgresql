package com.zonacards.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.eventx.EventxRuntime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AppModuleTest {

  @Test
  void aModuleIsAssembledOnlyWhenItIsStarted() {
    AtomicInteger assembled = new AtomicInteger();
    AppModule module =
        AppModule.of(
            "lotes",
            "/api/v1/lotes",
            () -> {
              assembled.incrementAndGet();
              return new EventxRuntime(null, List.of());
            });

    // Declaring a module costs nothing: this is the state of a module that is not served.
    assertEquals(0, assembled.get());
    assertFalse(module.running());
    assertTrue(module.heartbeats().isEmpty());

    module.start();
    module.start();
    assertEquals(1, assembled.get(), "assembled once");
    assertTrue(module.running());

    module.stop(Duration.ofSeconds(1));
    assertFalse(module.running());
  }

  @Test
  void aModuleThatCannotBeAssembledDoesNotStart() {
    AppModule module =
        AppModule.of(
            "giftcards",
            "/api/v1/giftcards",
            () -> {
              throw new IllegalStateException("module 'giftcards' cannot be served by this deployment");
            });
    assertThrows(IllegalStateException.class, module::start);
    assertFalse(module.running());
    // Stopping what never started is harmless: the lifecycle does it when a start fails halfway.
    module.stop(Duration.ofSeconds(1));
  }

  @Test
  void theApiPathIsAPrefixOfWholeSegments() {
    AppModule module = AppModule.of("lotes", "/api/v1/lotes", () -> new EventxRuntime(null, List.of()));
    assertTrue(module.owns("/api/v1/lotes"));
    assertTrue(module.owns("/api/v1/lotes/0190-abc"));
    assertFalse(module.owns("/api/v1/lotesx"));
    assertFalse(module.owns("/api/v1"));
  }
}
