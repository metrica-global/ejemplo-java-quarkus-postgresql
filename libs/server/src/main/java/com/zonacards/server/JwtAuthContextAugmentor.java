package com.zonacards.server;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Reads the verified access token once and exposes what the application needs as identity
 * attributes. Signature, issuer, audience and expiry were already checked by the OIDC extension;
 * this class only maps claims:
 *
 * <ul>
 *   <li>{@code tenant_id}: the tenant the caller belongs to. No tenant, no access.
 *   <li>{@code scope}: the OAuth scopes granted, space separated.
 *   <li>the actor: a service account (client credentials) or a person.
 * </ul>
 *
 * Nothing here comes from the request body or from headers the caller controls.
 */
@ApplicationScoped
public class JwtAuthContextAugmentor implements SecurityIdentityAugmentor {

  private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

  @Override
  public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
    if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
      return Uni.createFrom().item(identity);
    }
    QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity);
    builder.addAttribute(AuthAttributes.TENANT, text(token.getClaim("tenant_id")));
    builder.addAttribute(AuthAttributes.SCOPES, text(token.getClaim("scope")));

    String username = text(token.getClaim("preferred_username"));
    String clientId = firstNonBlank(text(token.getClaim("client_id")), text(token.getClaim("azp")));
    boolean serviceAccount = username.isBlank() || username.startsWith(SERVICE_ACCOUNT_PREFIX);
    if (serviceAccount) {
      builder.addAttribute(AuthAttributes.ACTOR_KIND, "CLIENT");
      builder.addAttribute(AuthAttributes.ACTOR_ID, clientId);
      builder.addAttribute(AuthAttributes.NAME, clientId);
    } else {
      builder.addAttribute(AuthAttributes.ACTOR_KIND, "USER");
      builder.addAttribute(AuthAttributes.ACTOR_ID, firstNonBlank(token.getSubject(), username));
      builder.addAttribute(AuthAttributes.NAME, username);
    }
    return Uni.createFrom().item(builder.build());
  }

  private static String text(Object claim) {
    if (claim == null) {
      return "";
    }
    if (claim instanceof JsonString json) {
      return json.getString();
    }
    return claim.toString();
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : (b == null ? "" : b);
  }
}
