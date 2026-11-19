package com.zonacards.eventx;

import java.util.List;

/** Port to the broker, producing side. */
public interface MessageSender {

  /** The batch limit of both SNS and SQS. */
  int MAX_BATCH = 10;

  /**
   * Sends up to {@link #MAX_BATCH} messages to one destination. Per-message rejections are
   * reported in the result; an exception means nothing is known to have been sent.
   */
  SendResult send(DestinationKind kind, String destination, List<OutboxMessage> batch);
}
