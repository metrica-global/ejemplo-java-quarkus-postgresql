package com.zonacards.eventx;

import java.time.Duration;
import java.util.List;

/** Port to the broker, consuming side. Queue names are logical names. */
public interface MessageReceiver {

  /** Long-polls: returns as soon as there is a message, or empty after {@code wait}. */
  List<ReceivedMessage> receive(String queue, int max, Duration wait, Duration visibilityTimeout);

  void delete(String queue, ReceivedMessage message);

  /** Zero gives the message back at once; a longer value delays its redelivery. */
  void changeVisibility(String queue, ReceivedMessage message, Duration visibilityTimeout);
}
