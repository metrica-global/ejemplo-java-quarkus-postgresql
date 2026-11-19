package com.zonacards.kernel;

import java.util.List;

/**
 * Trusted authentication context. It is built from a verified credential (or by the system for
 * event handlers) and passed explicitly to every service method. Never bind it from request JSON.
 */
public record AuthContext(Actor actor, TenantId tenantId, String name, List<String> scopes) {

  public AuthContext {
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
  }

  /** Context used by event handlers: the system acts on behalf of the tenant that owns the event. */
  public static AuthContext system(TenantId tenantId, String name, String... scopes) {
    return new AuthContext(Actor.system(name), tenantId, name, List.of(scopes));
  }

  public boolean isValid() {
    return actor != null && actor.isValid() && tenantId != null && !tenantId.isEmpty();
  }

  public boolean hasScope(String required) {
    return Scopes.contain(scopes, required);
  }

  public boolean hasAnyScope(String... required) {
    for (String scope : required) {
      if (hasScope(scope)) {
        return true;
      }
    }
    return false;
  }

  public boolean hasAllScopes(String... required) {
    for (String scope : required) {
      if (!hasScope(scope)) {
        return false;
      }
    }
    return true;
  }
}
