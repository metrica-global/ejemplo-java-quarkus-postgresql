package com.zonacards.kernel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScopesTest {

  @Test
  void exactMatch() {
    assertTrue(Scopes.match("lotes:read", "lotes:read"));
    assertFalse(Scopes.match("lotes:read", "lotes:write"));
  }

  @Test
  void tenantWildcardGrantsEveryApplicationScope() {
    assertTrue(Scopes.match("*", "giftcards:anular"));
  }

  @Test
  void prefixWildcardGrantsOnlyItsOwnNamespace() {
    assertTrue(Scopes.match("lotes:*", "lotes:read"));
    assertTrue(Scopes.match("lotes:*", "lotes:fragmentos:read"));
    assertFalse(Scopes.match("lotes:*", "lotes"));
    assertFalse(Scopes.match("lotes:*", "lotesx:read"));
    assertFalse(Scopes.match("lotes:*", "giftcards:read"));
  }

  @Test
  void platformScopesAreInertEvenWhenTheyMatchExactly() {
    assertFalse(Scopes.match("platform:tenants:read", "platform:tenants:read"));
    assertFalse(Scopes.match("*", "platform:tenants:read"));
    assertFalse(Scopes.match("platform:*", "lotes:read"));
  }

  @Test
  void blankOrNullNeverMatches() {
    assertFalse(Scopes.match(null, "lotes:read"));
    assertFalse(Scopes.match("lotes:read", null));
    assertFalse(Scopes.match("", "lotes:read"));
    assertFalse(Scopes.match("*", " "));
    assertFalse(Scopes.contain(null, "lotes:read"));
  }

  @Test
  void containChecksEveryHeldScope() {
    assertTrue(Scopes.contain(List.of("giftcards:read", "lotes:*"), "lotes:write"));
    assertFalse(Scopes.contain(List.of("giftcards:read"), "lotes:write"));
  }
}
