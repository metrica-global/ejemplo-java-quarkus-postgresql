package com.zonacards.testkit;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * The local SNS/SQS emulator (Floci), shared by every test of a JVM, with helpers to build the
 * same kind of topology Terraform builds in AWS: topic, queue with dead-letter queue, and a
 * filtered subscription with raw message delivery.
 */
public final class FlociFixture {

  public static final String IMAGE = "floci/floci:2.5.0";
  public static final String REGION = "us-east-1";
  public static final String ACCESS_KEY = "test";
  public static final String SECRET_KEY = "test";

  private static FlociFixture shared;

  private final GenericContainer<?> container;
  private final URI attachedEndpoint;
  private final SqsClient sqs;
  private final SnsClient sns;

  /** Connects to an emulator that is already running, without starting a container. */
  public static FlociFixture attach(URI endpoint) {
    return new FlociFixture(endpoint);
  }

  private FlociFixture(URI endpoint) {
    this.container = null;
    this.attachedEndpoint = endpoint;
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
    this.sqs =
        SqsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    this.sns =
        SnsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
  }

  private FlociFixture() {
    attachedEndpoint = null;
    container =
        new GenericContainer<>(IMAGE)
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));
    container.start();
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
    sqs =
        SqsClient.builder()
            .endpointOverride(endpoint())
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    sns =
        SnsClient.builder()
            .endpointOverride(endpoint())
            .region(Region.of(REGION))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
  }

  public static synchronized FlociFixture shared() {
    if (shared == null) {
      shared = new FlociFixture();
    }
    return shared;
  }

  public URI endpoint() {
    if (attachedEndpoint != null) {
      return attachedEndpoint;
    }
    return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(4566));
  }

  public SqsClient sqs() {
    return sqs;
  }

  public SnsClient sns() {
    return sns;
  }

  public String createTopic(String name) {
    return sns.createTopic(b -> b.name(name)).topicArn();
  }

  /** Creates a queue and its dead-letter queue ({@code <name>-dlq}). Returns the queue URL. */
  public String createQueue(String name, int visibilityTimeoutSeconds, int maxReceiveCount) {
    String dlq = sqs.createQueue(b -> b.queueName(name + "-dlq")).queueUrl();
    Map<QueueAttributeName, String> attributes = new HashMap<>();
    attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(visibilityTimeoutSeconds));
    attributes.put(
        QueueAttributeName.REDRIVE_POLICY,
        "{\"deadLetterTargetArn\":\"" + queueArn(dlq) + "\",\"maxReceiveCount\":\"" + maxReceiveCount + "\"}");
    return sqs.createQueue(b -> b.queueName(name).attributes(attributes)).queueUrl();
  }

  public String queueUrl(String name) {
    return sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
  }

  public String queueArn(String queueUrl) {
    return sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
        .attributes()
        .get(QueueAttributeName.QUEUE_ARN);
  }

  /** Subscribes a queue to a topic for the given event types, with raw message delivery. */
  public void subscribe(String topicArn, String queueUrl, List<String> eventTypes) {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("RawMessageDelivery", "true");
    if (eventTypes != null && !eventTypes.isEmpty()) {
      String types = eventTypes.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(","));
      attributes.put("FilterPolicy", "{\"type\":[" + types + "]}");
    }
    sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(queueArn(queueUrl)).attributes(attributes));
  }

  /** Visible messages in the queue: the number the autoscaling policy looks at. */
  public int depth(String queueUrl) {
    return Integer.parseInt(
        sqs.getQueueAttributes(
                b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
            .attributes()
            .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
  }

  /** Receives and deletes until {@code expected} messages arrived or the timeout expired. */
  public List<Message> drain(String queueUrl, int expected, Duration timeout) {
    List<Message> out = new ArrayList<>();
    long deadline = System.nanoTime() + timeout.toNanos();
    while (out.size() < expected && System.nanoTime() < deadline) {
      List<Message> batch =
          sqs.receiveMessage(
                  b -> b.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1).messageAttributeNames("All"))
              .messages();
      for (Message message : batch) {
        out.add(message);
        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
      }
    }
    return out;
  }
}
