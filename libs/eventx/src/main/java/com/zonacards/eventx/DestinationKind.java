package com.zonacards.eventx;

/**
 * Where an outbox row goes. A TOPIC carries events: facts for whoever subscribes. A QUEUE carries
 * commands that a domain sends to its own workers; they never leave the domain.
 */
public enum DestinationKind {
  TOPIC,
  QUEUE
}
