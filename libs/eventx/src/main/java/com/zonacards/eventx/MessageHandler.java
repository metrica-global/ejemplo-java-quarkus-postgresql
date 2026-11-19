package com.zonacards.eventx;

/**
 * Reacts to one message type. Throwing means "try again later": the message is redelivered with
 * backoff and ends in the dead-letter queue after the queue's maximum receive count. A business
 * outcome that will never succeed must not throw; record it and emit the corresponding event.
 */
@FunctionalInterface
public interface MessageHandler {

  void handle(MessageContext context);
}
