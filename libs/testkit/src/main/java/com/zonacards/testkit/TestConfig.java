package com.zonacards.testkit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Quarkus configuration that points the application under test at the fixtures. */
public final class TestConfig {

  public static final String AWS_ENDPOINT = "test.aws.endpoint";
  public static final String JWT_PRIVATE_KEY = "test.jwt.private-key";

  private TestConfig() {}

  /**
   * One datasource per module, as in production: the module's runtime role for the pool and its
   * owner role for Flyway. Only the listed modules get credentials; the others stay without,
   * which is how a deployment that does not serve a module looks.
   */
  public static Map<String, String> database(PostgresFixture postgres, String... modules) {
    Map<String, String> config = new HashMap<>();
    // The same variable the deployments receive; application.properties refers to it.
    config.put("ZC_DB_URL", postgres.jdbcUrl());
    for (String module : modules) {
      String upper = module.toUpperCase(Locale.ROOT);
      config.put("ZC_DB_" + upper + "_APP_USER", PostgresFixture.appUser(module));
      config.put("ZC_DB_" + upper + "_APP_PASSWORD", PostgresFixture.appPassword(module));
      config.put("quarkus.flyway." + module + ".jdbc-url", postgres.jdbcUrl());
      config.put("quarkus.flyway." + module + ".username", PostgresFixture.ownerUser(module));
      config.put("quarkus.flyway." + module + ".password", PostgresFixture.ownerPassword(module));
      config.put("quarkus.flyway." + module + ".migrate-at-start", "true");
    }
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
