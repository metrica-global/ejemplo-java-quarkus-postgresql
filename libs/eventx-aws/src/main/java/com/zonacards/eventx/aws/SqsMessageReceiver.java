package com.zonacards.eventx.aws;

import com.zonacards.eventx.MessageReceiver;
import com.zonacards.eventx.MessageSender;
import com.zonacards.eventx.ReceivedMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** Consumes SQS with long polling. */
public final class SqsMessageReceiver implements MessageReceiver {

  private final SqsClient sqs;
  private final Destinations destinations;

  public SqsMessageReceiver(SqsClient sqs, Destinations destinations) {
    this.sqs = sqs;
    this.destinations = destinations;
  }

  @Override
  public List<ReceivedMessage> receive(String queue, int max, Duration wait, Duration visibilityTimeout) {
    List<Message> messages =
        sqs.receiveMessage(
                b ->
                    b.queueUrl(destinations.queueUrl(queue))
                        .maxNumberOfMessages(Math.min(max, MessageSender.MAX_BATCH))
                        .waitTimeSeconds((int) wait.toSeconds())
                        .visibilityTimeout((int) visibilityTimeout.toSeconds())
                        .messageAttributeNames("All")
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
            .messages();
    List<ReceivedMessage> out = new ArrayList<>(messages.size());
    for (Message message : messages) {
      Map<String, String> attributes = new HashMap<>();
      message.messageAttributes().forEach((name, value) -> attributes.put(name, value.stringValue()));
      String count = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
      out.add(
          new ReceivedMessage(
              message.messageId(), message.receiptHandle(), message.body(), attributes, count == null ? 1 : Integer.parseInt(count)));
    }
    return out;
  }

  @Override
  public void delete(String queue, ReceivedMessage message) {
    sqs.deleteMessage(b -> b.queueUrl(destinations.queueUrl(queue)).receiptHandle(message.receiptHandle()));
  }

  @Override
  public void changeVisibility(String queue, ReceivedMessage message, Duration visibilityTimeout) {
    sqs.changeMessageVisibility(
        b ->
            b.queueUrl(destinations.queueUrl(queue))
                .receiptHandle(message.receiptHandle())
                .visibilityTimeout((int) visibilityTimeout.toSeconds()));
  }
}
