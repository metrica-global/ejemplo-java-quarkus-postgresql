package com.zonacards.eventx;

import java.util.Map;

/**
 * A message taken from a queue.
 *
 * @param messageId the broker's id (not the event id: the same event can arrive twice with
 *     different broker ids)
 * @param receiptHandle what the broker needs to delete it or change its visibility
 * @param receiveCount how many times the broker has delivered it, starting at 1
 */
public record ReceivedMessage(
    String messageId, String receiptHandle, String body, Map<String, String> attributes, int receiveCount) {}
