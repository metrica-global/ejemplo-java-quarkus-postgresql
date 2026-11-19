package com.zonacards.eventx.aws;

import com.zonacards.eventx.DestinationKind;
import com.zonacards.eventx.MessageSender;
import com.zonacards.eventx.OutboxMessage;
import com.zonacards.eventx.SendResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

/**
 * Publishes outbox messages: events to SNS, commands straight to SQS. Both in batches of up to
 * ten, which cuts the number of API calls (and their cost) by ten.
 *
 * <p>The body is the CloudEvents JSON. The event type also travels as a message attribute so the
 * subscription filter policies can route without parsing the body.
 */
public final class AwsMessageSender implements MessageSender {

  static final String ATTRIBUTE_TYPE = "type";
  static final String ATTRIBUTE_TENANT = "tenantid";

  private final SnsClient sns;
  private final SqsClient sqs;
  private final Destinations destinations;

  public AwsMessageSender(SnsClient sns, SqsClient sqs, Destinations destinations) {
    this.sns = sns;
    this.sqs = sqs;
    this.destinations = destinations;
  }

  @Override
  public SendResult send(DestinationKind kind, String destination, List<OutboxMessage> batch) {
    return kind == DestinationKind.TOPIC ? publish(destination, batch) : sendToQueue(destination, batch);
  }

  private SendResult publish(String topic, List<OutboxMessage> batch) {
    List<PublishBatchRequestEntry> entries = new ArrayList<>(batch.size());
    for (OutboxMessage message : batch) {
      entries.add(
          PublishBatchRequestEntry.builder()
              .id(message.id().toString())
              .message(message.payload())
              .messageAttributes(
                  Map.of(
                      ATTRIBUTE_TYPE, snsAttribute(message.type()),
                      ATTRIBUTE_TENANT, snsAttribute(message.tenantId())))
              .build());
    }
    PublishBatchResponse response =
        sns.publishBatch(b -> b.topicArn(destinations.topicArn(topic)).publishBatchRequestEntries(entries));
    Set<UUID> sent = new HashSet<>();
    response.successful().forEach(ok -> sent.add(UUID.fromString(ok.id())));
    Map<UUID, SendResult.Failure> failed = new HashMap<>();
    response
        .failed()
        .forEach(
            error ->
                failed.put(
                    UUID.fromString(error.id()),
                    new SendResult.Failure(error.code() + ": " + error.message(), Boolean.TRUE.equals(error.senderFault()))));
    return new SendResult(sent, failed);
  }

  private SendResult sendToQueue(String queue, List<OutboxMessage> batch) {
    List<SendMessageBatchRequestEntry> entries = new ArrayList<>(batch.size());
    for (OutboxMessage message : batch) {
      entries.add(
          SendMessageBatchRequestEntry.builder()
              .id(message.id().toString())
              .messageBody(message.payload())
              .messageAttributes(
                  Map.of(
                      ATTRIBUTE_TYPE, sqsAttribute(message.type()),
                      ATTRIBUTE_TENANT, sqsAttribute(message.tenantId())))
              .build());
    }
    SendMessageBatchResponse response = sqs.sendMessageBatch(b -> b.queueUrl(destinations.queueUrl(queue)).entries(entries));
    Set<UUID> sent = new HashSet<>();
    response.successful().forEach(ok -> sent.add(UUID.fromString(ok.id())));
    Map<UUID, SendResult.Failure> failed = new HashMap<>();
    response
        .failed()
        .forEach(
            error ->
                failed.put(
                    UUID.fromString(error.id()),
                    new SendResult.Failure(error.code() + ": " + error.message(), Boolean.TRUE.equals(error.senderFault()))));
    return new SendResult(sent, failed);
  }

  private static software.amazon.awssdk.services.sns.model.MessageAttributeValue snsAttribute(String value) {
    return software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
        .dataType("String")
        .stringValue(value)
        .build();
  }

  private static software.amazon.awssdk.services.sqs.model.MessageAttributeValue sqsAttribute(String value) {
    return software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
        .dataType("String")
        .stringValue(value)
        .build();
  }
}
