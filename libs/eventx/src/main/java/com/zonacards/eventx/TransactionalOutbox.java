package com.zonacards.eventx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The outbox: encodes the envelope and stores it through the caller's unit of work. */
public final class TransactionalOutbox implements Outbox {

  private final OutboxStore store;
  private final EventCodec codec;

  public TransactionalOutbox(OutboxStore store, EventCodec codec) {
    this.store = store;
    this.codec = codec;
  }

  @Override
  public void publish(String topic, EventEnvelope event) {
    store.add(List.of(toMessage(DestinationKind.TOPIC, topic, event)));
  }

  @Override
  public void send(String queue, EventEnvelope command) {
    store.add(List.of(toMessage(DestinationKind.QUEUE, queue, command)));
  }

  @Override
  public void sendAll(String queue, Collection<EventEnvelope> commands) {
    List<OutboxMessage> messages = new ArrayList<>(commands.size());
    for (EventEnvelope command : commands) {
      messages.add(toMessage(DestinationKind.QUEUE, queue, command));
    }
    store.add(messages);
  }

  private OutboxMessage toMessage(DestinationKind kind, String destination, EventEnvelope envelope) {
    return new OutboxMessage(
        UUID.fromString(envelope.id()),
        kind,
        destination,
        envelope.type(),
        envelope.tenantid(),
        codec.encode(envelope),
        0,
        envelope.time());
  }
}
