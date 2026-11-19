package com.zonacards.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.eventx.EventxRuntime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveModulesTest {

  private static final AppModule LOTES = module("lotes", "/api/v1/lotes");
  private static final AppModule GIFTCARDS = module("giftcards", "/api/v1/giftcards");

  private static AppModule module(String name, String apiPath) {
    return AppModule.of(name, apiPath, () -> new EventxRuntime(null, List.of()));
  }

  private static ActiveModules deployment(String... served) {
    return new ActiveModules(List.of(LOTES, GIFTCARDS), List.of(served));
  }

  @Test
  void aDeploymentServesTheModulesItLists() {
    assertEquals(List.of(LOTES, GIFTCARDS), deployment("lotes", "giftcards").served());
    assertEquals(List.of(GIFTCARDS), deployment("giftcards").served());
    // The order is the application's, not the one of the variable.
    assertEquals(List.of(LOTES, GIFTCARDS), deployment("giftcards", "lotes").served());
    deployment("lotes").validate();
  }

  @Test
  void aNameTheApplicationDoesNotContainStopsTheStart() {
    // Without this, ZC_MODULES=lote would give a healthy task that serves nothing.
    IllegalStateException misspelled = assertThrows(IllegalStateException.class, () -> deployment("lote").validate());
    assertTrue(misspelled.getMessage().contains("[lote]"), misspelled.getMessage());
    assertTrue(misspelled.getMessage().contains("[lotes, giftcards]"), misspelled.getMessage());
    assertThrows(IllegalStateException.class, () -> deployment("lotes", "redencion").validate());
  }

  @Test
  void aDeploymentThatServesNothingStopsTheStart() {
    IllegalStateException empty = assertThrows(IllegalStateException.class, () -> deployment().validate());
    assertTrue(empty.getMessage().contains("ZC_MODULES is empty"), empty.getMessage());
  }

  @Test
  void aPathBelongsToTheModuleThatOwnsItsPrefix() {
    ActiveModules onlyLotes = deployment("lotes");
    assertEquals(GIFTCARDS, onlyLotes.ownerNotServed("/api/v1/giftcards"));
    assertEquals(GIFTCARDS, onlyLotes.ownerNotServed("/api/v1/giftcards/0190-abc/activar"));
    assertEquals(GIFTCARDS, onlyLotes.ownerNotServed("api/v1/giftcards"));
    // Served here.
    assertNull(onlyLotes.ownerNotServed("/api/v1/lotes"));
    assertNull(onlyLotes.ownerNotServed("/api/v1/lotes/0190-abc"));
    // Nobody's: a longer word is not the prefix, and neither are the operational endpoints.
    assertNull(onlyLotes.ownerNotServed("/api/v1/giftcardsx"));
    assertNull(onlyLotes.ownerNotServed("/q/health/live"));
    assertNull(onlyLotes.ownerNotServed("/"));
  }
}
