package com.zonacards.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PreflightTest {

  @Test
  void aDeploymentThatHasEverythingPasses() {
    Preflight.of("giftcards").require("the key", true).check("queue giftcards-inbox", () -> {}).verify();
  }

  @Test
  void everyProblemIsReportedAtOnce() {
    Preflight preflight =
        Preflight.of("giftcards")
            .require("ZC_GIFTCARDS_CODIGO_KEY", false)
            .require("ZC_GIFTCARDS_CODIGO_HMAC_KEY", true)
            .check(
                "queue giftcards-inbox",
                () -> {
                  throw new IllegalStateException("no URL configured for queue 'giftcards-inbox'");
                })
            .check("queue giftcards-generacion", () -> {});

    IllegalStateException failure = assertThrows(IllegalStateException.class, preflight::verify);
    assertEquals(
        "module 'giftcards' cannot be served by this deployment: ZC_GIFTCARDS_CODIGO_KEY is not configured; "
            + "queue giftcards-inbox: no URL configured for queue 'giftcards-inbox'",
        failure.getMessage());
  }
}
