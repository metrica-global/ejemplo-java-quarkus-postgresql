package com.zonacards.giftcards;

import com.zonacards.testkit.FlociFixture;
import com.zonacards.testkit.PostgresFixture;
import com.zonacards.testkit.TestConfig;
import com.zonacards.testkit.TestTokens;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 18 with the production roles, and the messaging topology of the giftcards module.
 * Nothing of lotes is provided; with {@link OnlyGiftcards} the application runs as a deployment
 * that serves only this module, the way it does when the module is promoted to its own service.
 */
public class GiftcardsTestResource implements QuarkusTestResourceLifecycleManager {

  public static final String INBOX_URL = "test.giftcards.inbox-url";
  public static final String WORK_URL = "test.giftcards.work-url";
  public static final String SPY_URL = "test.giftcards.spy-url";

  @Override
  public Map<String, String> start() {
    PostgresFixture postgres = PostgresFixture.shared().bootstrapService("giftcards", 30);
    FlociFixture floci = FlociFixture.shared();
    String topicArn = floci.createTopic("it-giftcards-events");
    String inboxUrl = floci.createQueue("it-giftcards-inbox", 30, 3);
    String workUrl = floci.createQueue("it-giftcards-generacion", 60, 3);
    String spyUrl = floci.createQueue("it-giftcards-events-spy", 30, 5);
    floci.subscribe(topicArn, spyUrl, List.of());

    Map<String, String> config = new HashMap<>();
    config.putAll(TestConfig.database(postgres, "giftcards"));
    config.putAll(TestConfig.aws(floci));
    config.putAll(TestConfig.oidc(TestTokens.generate()));
    config.put("zonacards.messaging.topics.\"giftcards-events\"", topicArn);
    config.put("zonacards.messaging.queues.\"giftcards-inbox\"", inboxUrl);
    config.put("zonacards.messaging.queues.\"giftcards-generacion\"", workUrl);
    config.put("zonacards.messaging.consumer.wait-time", "PT1S");
    config.put("zonacards.messaging.relay.poll-interval", "PT0.05S");
    config.put("zonacards.giftcards.codigo.key", Fixtures.KEY);
    config.put("zonacards.giftcards.codigo.hmac-key", Fixtures.HMAC_KEY);
    // The real algorithm at a low cost, and small fragments, so a lote takes seconds.
    config.put("zonacards.giftcards.argon2.memory-kib", "256");
    config.put("zonacards.giftcards.argon2.iterations", "1");
    config.put("zonacards.giftcards.generacion.fragment-size", "100");
    config.put("zonacards.giftcards.generacion.visibility-timeout", "PT60S");
    config.put(INBOX_URL, inboxUrl);
    config.put(WORK_URL, workUrl);
    config.put(SPY_URL, spyUrl);
    return config;
  }

  @Override
  public void stop() {
    // Containers are removed by Testcontainers when the JVM exits.
  }

  /** The application as a deployment with {@code ZC_MODULES=giftcards}. */
  public static class OnlyGiftcards implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("zonacards.modules", "giftcards");
    }

    @Override
    public List<TestResourceEntry> testResources() {
      return List.of(new TestResourceEntry(GiftcardsTestResource.class));
    }
  }
}
