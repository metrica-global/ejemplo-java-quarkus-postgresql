package com.zonacards.eventx.memory;

import com.zonacards.eventx.DestinationKind;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.Outbox;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** An outbox that just remembers what was emitted. For unit tests of services. */
public final class RecordingOutbox implements Outbox {

  public record Recorded(DestinationKind kind, String destination, EventEnvelope envelope) {}

  private final List<Recorded> recorded = new ArrayList<>();

  @Override
  public synchronized void publish(String topic, EventEnvelope event) {
    recorded.add(new Recorded(DestinationKind.TOPIC, topic, event));
  }

  @Override
  public synchronized void send(String queue, EventEnvelope command) {
    recorded.add(new Recorded(DestinationKind.QUEUE, queue, command));
  }

  @Override
  public synchronized void sendAll(String queue, Collection<EventEnvelope> commands) {
    for (EventEnvelope command : commands) {
      recorded.add(new Recorded(DestinationKind.QUEUE, queue, command));
    }
  }

  public synchronized List<Recorded> all() {
    return List.copyOf(recorded);
  }

  public synchronized List<EventEnvelope> ofType(String type) {
    return recorded.stream().map(Recorded::envelope).filter(e -> e.type().equals(type)).toList();
  }

  public synchronized void clear() {
    recorded.clear();
  }
}
