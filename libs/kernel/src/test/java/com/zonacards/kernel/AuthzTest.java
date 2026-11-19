package com.zonacards.kernel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.Errx;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthzTest {

  private static final TenantId TENANT = TenantId.of("pe");
  private static final TenantId OTHER = TenantId.of("ec");

  private static AuthContext user(TenantId tenant, String... scopes) {
    return new AuthContext(Actor.user(UserId.of("u-1")), tenant, "Ana", List.of(scopes));
  }

  private static void assertCode(Errx error, com.zonacards.errx.ErrorCode code) {
    assertTrue(error.hasCode(code), "expected " + code.code() + " but was " + error.code());
  }

  @Test
  void allowsExactScopeWildcardAndPrefix() {
    assertDoesNotThrow(() -> Authz.require(user(TENANT, "lotes:read"), TENANT, "lotes:read"));
    assertDoesNotThrow(() -> Authz.require(user(TENANT, "lotes:*"), TENANT, "lotes:read"));
    assertDoesNotThrow(() -> Authz.require(user(TENANT, "*"), TENANT, "lotes:read"));
  }

  @Test
  void allowsClientAndSystemActors() {
    AuthContext client = new AuthContext(Actor.client(ClientId.of("pos")), TENANT, "pos", List.of("lotes:read"));
    assertDoesNotThrow(() -> Authz.require(client, TENANT, "lotes:read"));
    assertDoesNotThrow(() -> Authz.require(AuthContext.system(TENANT, "eventx", "lotes:write"), TENANT, "lotes:write"));
  }

  @Test
  void missingOrInvalidIdentityIsUnauthorized() {
    assertCode(assertThrows(Errx.class, () -> Authz.require(null, TENANT, "lotes:read")), KernelErrors.UNAUTHORIZED);
    AuthContext noActor = new AuthContext(Actor.user(UserId.of("")), TENANT, "x", List.of("*"));
    assertCode(assertThrows(Errx.class, () -> Authz.require(noActor, TENANT, "lotes:read")), KernelErrors.UNAUTHORIZED);
    AuthContext noTenant = new AuthContext(Actor.user(UserId.of("u")), TenantId.of(""), "x", List.of("*"));
    assertCode(assertThrows(Errx.class, () -> Authz.require(noTenant, TENANT, "lotes:read")), KernelErrors.UNAUTHORIZED);
  }

  @Test
  void theWildcardNeverCrossesTheTenantBoundary() {
    assertCode(
        assertThrows(Errx.class, () -> Authz.require(user(OTHER, "*"), TENANT, "lotes:read")),
        KernelErrors.ACCESS_DENIED);
  }

  @Test
  void missingWrongOrBlankPermissionIsDenied() {
    assertCode(assertThrows(Errx.class, () -> Authz.require(user(TENANT), TENANT, "lotes:read")), KernelErrors.ACCESS_DENIED);
    assertCode(
        assertThrows(Errx.class, () -> Authz.require(user(TENANT, "lotes:read"), TENANT, "lotes:write")),
        KernelErrors.ACCESS_DENIED);
    assertCode(assertThrows(Errx.class, () -> Authz.require(user(TENANT, "*"), TENANT, " ")), KernelErrors.ACCESS_DENIED);
    assertCode(assertThrows(Errx.class, () -> Authz.require(user(TENANT, "*"), TenantId.of(""), "lotes:read")), KernelErrors.ACCESS_DENIED);
    assertCode(
        assertThrows(Errx.class, () -> Authz.require(user(TENANT, "*"), TENANT, "platform:tenants:read")),
        KernelErrors.ACCESS_DENIED);
  }
}
