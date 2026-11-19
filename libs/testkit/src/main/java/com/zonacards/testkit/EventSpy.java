package com.zonacards.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * A queue subscribed to a topic with no filter, read by the test: it sees every event a service
 * publishes, exactly as another domain would receive it.
 */
public final class EventSpy {

  /**
   * @param raw the body exactly as it travelled, to assert on the wire format itself
   * @param typeAttribute the {@code type} message attribute, the one SNS filter policies look at
   */
  public record Received(JsonNode event, String raw, String typeAttribute) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final FlociFixture floci;
  private final String queueUrl;
  private final List<Received> seen = new ArrayList<>();

  public EventSpy(FlociFixture floci, String queueUrl) {
    this.floci = floci;
    this.queueUrl = queueUrl;
  }

  /** Waits for an event of the type about the subject. Fails if it does not arrive in time. */
  public synchronized Received await(String type, String subject, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      for (Received received : seen) {
        if (matches(received, type, subject)) {
          return received;
        }
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("no " + type + " about " + subject + " within " + timeout + "; saw " + seen.size() + " events");
      }
      poll();
    }
  }

  /** Keeps listening for {@code settle} and then says how many matching events arrived in total. */
  public synchronized long count(String type, String subject, Duration settle) {
    long deadline = System.nanoTime() + settle.toNanos();
    while (System.nanoTime() < deadline) {
      poll();
    }
    return seen.stream().filter(received -> matches(received, type, subject)).count();
  }

  /** Every matching event seen so far. */
  public synchronized List<Received> all(String type, String subject) {
    return seen.stream().filter(received -> matches(received, type, subject)).toList();
  }

  private static boolean matches(Received received, String type, String subject) {
    return type.equals(received.event().path("type").asText()) && subject.equals(received.event().path("subject").asText());
  }

  private void poll() {
    for (Message message : floci.drain(queueUrl, 10, Duration.ofMillis(300))) {
      try {
        var attribute = message.messageAttributes().get("type");
        seen.add(new Received(MAPPER.readTree(message.body()), message.body(), attribute == null ? null : attribute.stringValue()));
      } catch (Exception e) {
        throw new AssertionError("a published message is not JSON: " + message.body(), e);
      }
    }
  }
}
