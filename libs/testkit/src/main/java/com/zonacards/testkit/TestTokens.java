package com.zonacards.testkit;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Access tokens for tests, shaped like the ones Keycloak issues and signed with a key generated
 * for the test run. The service verifies them with the real OIDC code path (signature, expiry,
 * audience), so the tests exercise the same claim mapping as production.
 */
public final class TestTokens {

  public static final String AUDIENCE = "zonacards-api";
  public static final String ISSUER = "https://issuer.test/realms/zonacards";

  private final PrivateKey privateKey;
  private final String publicKey;

  private TestTokens(PrivateKey privateKey, String publicKey) {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
  }

  public static TestTokens generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      return new TestTokens(pair.getPrivate(), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Rebuilds the signer from {@link #privateKeyBase64()}, on the other side of a class loader. */
  public static TestTokens ofPrivateKey(String base64) {
    try {
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
      return new TestTokens(key, null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String publicKeyBase64() {
    return publicKey;
  }

  public String privateKeyBase64() {
    return Base64.getEncoder().encodeToString(privateKey.getEncoded());
  }

  /** A person: {@code preferred_username} is the login name. */
  public String user(String tenantId, String username, String... scopes) {
    return claims(tenantId, scopes)
        .subject("user-" + username)
        .claim("preferred_username", username)
        .claim("azp", "zonacards-web")
        .jws()
        .sign(privateKey);
  }

  /** A machine client (client credentials grant), as Keycloak represents a service account. */
  public String client(String tenantId, String clientId, String... scopes) {
    return claims(tenantId, scopes)
        .subject("sa-" + clientId)
        .claim("preferred_username", "service-account-" + clientId)
        .claim("client_id", clientId)
        .claim("azp", clientId)
        .jws()
        .sign(privateKey);
  }

  /** A valid signature but meant for another API. */
  public String forAnotherAudience(String tenantId, String... scopes) {
    return Jwt.claims()
        .issuer(ISSUER)
        .audience("another-api")
        .subject("user-x")
        .claim("preferred_username", "x")
        .claim("tenant_id", tenantId)
        .claim("scope", String.join(" ", scopes))
        .expiresIn(300)
        .jws()
        .sign(privateKey);
  }

  public String expired(String tenantId, String... scopes) {
    long now = System.currentTimeMillis() / 1000;
    return claims(tenantId, scopes).subject("user-late").claim("preferred_username", "late").issuedAt(now - 7200).expiresAt(now - 3600).jws().sign(privateKey);
  }

  private static JwtClaimsBuilder claims(String tenantId, String... scopes) {
    JwtClaimsBuilder builder =
        Jwt.claims().issuer(ISSUER).audience(AUDIENCE).claim("scope", String.join(" ", scopes)).expiresIn(300);
    if (tenantId != null) {
      builder.claim("tenant_id", tenantId);
    }
    return builder;
  }
}
