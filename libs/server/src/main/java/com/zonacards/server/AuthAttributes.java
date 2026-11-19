package com.zonacards.server;

/**
 * Names of the security-identity attributes the {@link CurrentAuth} bean reads. They are filled
 * from the verified token by {@link JwtAuthContextAugmentor}, in one place.
 */
public final class AuthAttributes {

  public static final String TENANT = "zc.tenant";
  /** Space-separated scopes, as in the OAuth {@code scope} claim. */
  public static final String SCOPES = "zc.scopes";
  /** {@code USER} or {@code CLIENT}. */
  public static final String ACTOR_KIND = "zc.actor.kind";
  public static final String ACTOR_ID = "zc.actor.id";
  public static final String NAME = "zc.name";

  private AuthAttributes() {}
}
