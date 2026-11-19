package com.zonacards.eventx;

import java.util.Collection;

/**
 * The only way a service emits messages. Calls must happen inside the unit of work that changes
 * the aggregate: the message is stored in the same database transaction and delivered afterwards
 * by the relay. Either the state change and its message both happen, or neither does.
 *
 * <p>Delivery is at-least-once and unordered. Consumers must be idempotent.
 */
public interface Outbox {

  /** Publishes a fact to a topic. Logical topic name, not an ARN. */
  void publish(String topic, EventEnvelope event);

  /** Sends a command to a work queue owned by the caller's own domain. */
  void send(String queue, EventEnvelope command);

  /** Sends many commands to one queue with a single round trip to the database. */
  void sendAll(String queue, Collection<EventEnvelope> commands);
}
