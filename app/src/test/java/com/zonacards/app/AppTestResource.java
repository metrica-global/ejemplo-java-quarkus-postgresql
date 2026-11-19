package com.zonacards.app;

import com.zonacards.giftcards.Fixtures;
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
 * What the application needs to serve every module in one process: the database with the roles
 * of both modules, and the messaging topology Terraform creates, with its subscriptions and
 * their filters. Nothing here is a shortcut for "same process": the two modules still find each
 * other only through the topics and the queues.
 */
public class AppTestResource implements QuarkusTestResourceLifecycleManager {

  public static final String WORK_URL = "test.app.work-url";

  @Override
  public Map<String, String> start() {
    PostgresFixture postgres = PostgresFixture.shared().bootstrapService("lotes", 30).bootstrapService("giftcards", 30);
    FlociFixture floci = FlociFixture.shared();
    String lotesEvents = floci.createTopic("it-app-lotes-events");
    String giftcardsEvents = floci.createTopic("it-app-giftcards-events");
    String lotesInbox = floci.createQueue("it-app-lotes-inbox", 30, 3);
    String giftcardsInbox = floci.createQueue("it-app-giftcards-inbox", 30, 3);
    String work = floci.createQueue("it-app-giftcards-generacion", 60, 3);
    // The same subscriptions and filters as infra/terraform/modules/messaging/topology.
    floci.subscribe(
        lotesEvents, giftcardsInbox, List.of("zonacards.lotes.lote-solicitado.v1", "zonacards.lotes.lote-anulado.v1"));
    floci.subscribe(
        giftcardsEvents,
        lotesInbox,
        List.of(
            "zonacards.giftcards.generacion-iniciada.v1",
            "zonacards.giftcards.fragmento-generado.v1",
            "zonacards.giftcards.fragmento-fallido.v1",
            "zonacards.giftcards.lote-anulado-aplicado.v1"));

    Map<String, String> config = new HashMap<>();
    config.putAll(TestConfig.database(postgres, "lotes", "giftcards"));
    config.putAll(TestConfig.aws(floci));
    config.putAll(TestConfig.oidc(TestTokens.generate()));
    config.put("zonacards.messaging.topics.\"lotes-events\"", lotesEvents);
    config.put("zonacards.messaging.topics.\"giftcards-events\"", giftcardsEvents);
    config.put("zonacards.messaging.queues.\"lotes-inbox\"", lotesInbox);
    config.put("zonacards.messaging.queues.\"giftcards-inbox\"", giftcardsInbox);
    config.put("zonacards.messaging.queues.\"giftcards-generacion\"", work);
    config.put("zonacards.messaging.consumer.wait-time", "PT1S");
    config.put("zonacards.messaging.relay.poll-interval", "PT0.05S");
    config.put("zonacards.giftcards.codigo.key", Fixtures.KEY);
    config.put("zonacards.giftcards.codigo.hmac-key", Fixtures.HMAC_KEY);
    // The real algorithm at a low cost, and small fragments, so a lote takes seconds.
    config.put("zonacards.giftcards.argon2.memory-kib", "256");
    config.put("zonacards.giftcards.argon2.iterations", "1");
    config.put("zonacards.giftcards.generacion.fragment-size", "100");
    config.put("zonacards.giftcards.generacion.visibility-timeout", "PT60S");
    config.put(WORK_URL, work);
    return config;
  }

  @Override
  public void stop() {
    // Containers are removed by Testcontainers when the JVM exits.
  }

  /** The application as a deployment with {@code ZC_MODULES=lotes,giftcards}: the default one. */
  public static class EveryModule implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("zonacards.modules", "lotes,giftcards");
    }

    @Override
    public List<TestResourceEntry> testResources() {
      return List.of(new TestResourceEntry(AppTestResource.class));
    }
  }
}
