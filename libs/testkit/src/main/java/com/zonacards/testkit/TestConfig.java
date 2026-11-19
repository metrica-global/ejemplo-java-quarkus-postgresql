package com.zonacards.testkit;

import java.util.HashMap;
import java.util.Map;

/** Quarkus configuration that points a service under test at the fixtures. */
public final class TestConfig {

  public static final String AWS_ENDPOINT = "test.aws.endpoint";
  public static final String JWT_PRIVATE_KEY = "test.jwt.private-key";

  private TestConfig() {}

  /** Runtime role for the pool, owner role for Flyway: the same split as in production. */
  public static Map<String, String> database(PostgresFixture postgres, String service) {
    Map<String, String> config = new HashMap<>();
    config.put("quarkus.datasource.jdbc.url", postgres.jdbcUrl());
    config.put("quarkus.datasource.username", PostgresFixture.appUser(service));
    config.put("quarkus.datasource.password", PostgresFixture.appPassword(service));
    config.put("quarkus.flyway.jdbc-url", postgres.jdbcUrl());
    config.put("quarkus.flyway.username", PostgresFixture.ownerUser(service));
    config.put("quarkus.flyway.password", PostgresFixture.ownerPassword(service));
    config.put("quarkus.flyway.migrate-at-start", "true");
    return config;
  }

  public static Map<String, String> aws(FlociFixture floci) {
    Map<String, String> config = new HashMap<>();
    for (String client : new String[] {"sqs", "sns"}) {
      config.put("quarkus." + client + ".endpoint-override", floci.endpoint().toString());
      config.put("quarkus." + client + ".aws.region", FlociFixture.REGION);
      config.put("quarkus." + client + ".aws.credentials.type", "static");
      config.put("quarkus." + client + ".aws.credentials.static-provider.access-key-id", FlociFixture.ACCESS_KEY);
      config.put("quarkus." + client + ".aws.credentials.static-provider.secret-access-key", FlociFixture.SECRET_KEY);
    }
    config.put(AWS_ENDPOINT, floci.endpoint().toString());
    return config;
  }

  /** Tokens are verified against a local key instead of a running identity provider. */
  public static Map<String, String> oidc(TestTokens tokens) {
    Map<String, String> config = new HashMap<>();
    config.put("quarkus.oidc.public-key", tokens.publicKeyBase64());
    config.put(JWT_PRIVATE_KEY, tokens.privateKeyBase64());
    return config;
  }
}
