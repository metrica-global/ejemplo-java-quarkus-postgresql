package com.zonacards.lotes;

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
 * What the application needs to serve the lotes module for real: PostgreSQL 18 with the
 * production roles, and the SNS/SQS emulator with the same kind of topology Terraform creates. A
 * "spy" queue subscribed to the topic lets the tests see what the module publishes, as another
 * module would.
 *
 * <p>Nothing of giftcards is provided: no credentials, no queues, no keys. With {@link
 * OnlyLotes} the application runs as a deployment that serves only this module.
 */
public class LotesTestResource implements QuarkusTestResourceLifecycleManager {

  public static final String INBOX_URL = "test.lotes.inbox-url";
  public static final String SPY_URL = "test.lotes.spy-url";

  @Override
  public Map<String, String> start() {
    PostgresFixture postgres = PostgresFixture.shared().bootstrapService("lotes", 30);
    FlociFixture floci = FlociFixture.shared();
    String topicArn = floci.createTopic("it-lotes-events");
    String inboxUrl = floci.createQueue("it-lotes-inbox", 30, 3);
    String spyUrl = floci.createQueue("it-lotes-events-spy", 30, 5);
    floci.subscribe(topicArn, spyUrl, List.of());

    Map<String, String> config = new HashMap<>();
    config.putAll(TestConfig.database(postgres, "lotes"));
    config.putAll(TestConfig.aws(floci));
    config.putAll(TestConfig.oidc(TestTokens.generate()));
    config.put("zonacards.messaging.topics.\"lotes-events\"", topicArn);
    config.put("zonacards.messaging.queues.\"lotes-inbox\"", inboxUrl);
    config.put("zonacards.messaging.consumer.wait-time", "PT1S");
    config.put("zonacards.messaging.relay.poll-interval", "PT0.05S");
    config.put(INBOX_URL, inboxUrl);
    config.put(SPY_URL, spyUrl);
    return config;
  }

  @Override
  public void stop() {
    // Containers are removed by Testcontainers when the JVM exits.
  }

  /** The application as a deployment with {@code ZC_MODULES=lotes}. */
  public static class OnlyLotes implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("zonacards.modules", "lotes");
    }

    @Override
    public List<TestResourceEntry> testResources() {
      return List.of(new TestResourceEntry(LotesTestResource.class));
    }
  }
}
