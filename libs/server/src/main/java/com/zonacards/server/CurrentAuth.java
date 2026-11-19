package com.zonacards.server;

import com.zonacards.kernel.Actor;
import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.ClientId;
import com.zonacards.kernel.TenantId;
import com.zonacards.kernel.UserId;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the {@link AuthContext} of the current request from the verified identity. Resources
 * inject it and pass the context explicitly to the service: the service never reaches into a
 * thread-local or a framework object to find out who is calling.
 */
@RequestScoped
public class CurrentAuth {

  @Inject SecurityIdentity identity;

  /** The context of the caller, or null when the request is anonymous. */
  public AuthContext get() {
    if (identity == null || identity.isAnonymous()) {
      return null;
    }
    String principal = identity.getPrincipal() == null ? "" : identity.getPrincipal().getName();
    String tenant = attribute(AuthAttributes.TENANT, "");
    String scopes = attribute(AuthAttributes.SCOPES, "");
    String kind = attribute(AuthAttributes.ACTOR_KIND, "USER");
    String actorId = attribute(AuthAttributes.ACTOR_ID, principal);
    String name = attribute(AuthAttributes.NAME, principal);
    Actor actor = "CLIENT".equals(kind) ? Actor.client(ClientId.of(actorId)) : Actor.user(UserId.of(actorId));
    List<String> granted = scopes.isBlank() ? List.of() : Arrays.asList(scopes.trim().split("\\s+"));
    return new AuthContext(actor, TenantId.of(tenant), name, granted);
  }

  /** The tenant of the caller, or an empty id when anonymous (which authorization then rejects). */
  public TenantId tenantId() {
    AuthContext auth = get();
    return auth == null ? TenantId.of("") : auth.tenantId();
  }

  private String attribute(String name, String fallback) {
    Object value = identity.getAttribute(name);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }
}
