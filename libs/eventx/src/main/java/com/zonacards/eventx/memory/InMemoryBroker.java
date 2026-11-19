package com.zonacards.eventx.memory;

import com.zonacards.eventx.DestinationKind;
import com.zonacards.eventx.EventxException;
import com.zonacards.eventx.MessageReceiver;
import com.zonacards.eventx.MessageSender;
import com.zonacards.eventx.OutboxMessage;
import com.zonacards.eventx.ReceivedMessage;
import com.zonacards.eventx.SendResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A small broker with the semantics the library relies on in SNS and SQS: topics that fan out
 * to subscribed queues filtered by event type, visibility timeouts, receive counts, and redrive
 * to a dead-letter queue. It exists so the relay and the consumers can be tested, including
 * their failure paths, in milliseconds and without Docker.
 */
public final class InMemoryBroker implements MessageSender, MessageReceiver {

  private record Subscription(String queue, Set<String> types) {}

  private static final class Entry {
    final String id = UUID.randomUUID().toString();
    final String body;
    final Map<String, String> attributes;
    int receiveCount;
    long visibleAt;
    String receipt;

    Entry(String body, Map<String, String> attributes) {
      this.body = body;
      this.attributes = attributes;
    }
  }

  private static final class Queue {
    final List<Entry> entries = new ArrayList<>();
    final int maxReceiveCount;
    int delivered;

    Queue(int maxReceiveCount) {
      this.maxReceiveCount = maxReceiveCount;
    }
  }

  private final Object lock = new Object();
  private final Map<String, List<Subscription>> topics = new HashMap<>();
  private final Map<String, Queue> queues = new HashMap<>();
  private Predicate<OutboxMessage> rejected = m -> false;
  private Predicate<OutboxMessage> unavailable = m -> false;
  private boolean down;
  private int sendCalls;

  public InMemoryBroker topic(String name) {
    synchronized (lock) {
      topics.putIfAbsent(name, new ArrayList<>());
    }
    return this;
  }

  /** Creates the queue and its dead-letter queue, {@code <name>-dlq}. */
  public InMemoryBroker queue(String name, int maxReceiveCount) {
    synchronized (lock) {
      queues.putIfAbsent(name, new Queue(maxReceiveCount));
      queues.putIfAbsent(name + "-dlq", new Queue(Integer.MAX_VALUE));
    }
    return this;
  }

  /** Subscribes a queue to a topic. With no types, the queue receives everything. */
  public InMemoryBroker subscribe(String topic, String queue, String... types) {
    synchronized (lock) {
      topics.computeIfAbsent(topic, t -> new ArrayList<>()).add(new Subscription(queue, Set.of(types)));
    }
    return this;
  }

  /** Messages matching the predicate are rejected as invalid (a permanent failure). */
  public void rejectWhen(Predicate<OutboxMessage> predicate) {
    synchronized (lock) {
      rejected = predicate;
    }
  }

  /** Messages matching the predicate fail as if throttled (a transient failure). */
  public void failTransientlyWhen(Predicate<OutboxMessage> predicate) {
    synchronized (lock) {
      unavailable = predicate;
    }
  }

  /** While down, every send throws, like a network outage. */
  public void setDown(boolean down) {
    synchronized (lock) {
      this.down = down;
    }
  }

  public int sendCalls() {
    synchronized (lock) {
      return sendCalls;
    }
  }

  /** Messages in the queue, visible or not. */
  public int depth(String queue) {
    synchronized (lock) {
      return queue(queue).entries.size();
    }
  }

  /** How many messages have ever been put in the queue, including the ones already consumed. */
  public int delivered(String queue) {
    synchronized (lock) {
      return queue(queue).delivered;
    }
  }

  public List<String> bodies(String queue) {
    synchronized (lock) {
      return queue(queue).entries.stream().map(e -> e.body).toList();
    }
  }

  @Override
  public SendResult send(DestinationKind kind, String destination, List<OutboxMessage> batch) {
    synchronized (lock) {
      sendCalls++;
      if (down) {
        throw new EventxException("broker unavailable");
      }
      if (batch.size() > MAX_BATCH) {
        throw new EventxException("batch larger than " + MAX_BATCH);
      }
      Set<UUID> sent = new HashSet<>();
      Map<UUID, SendResult.Failure> failed = new HashMap<>();
      for (OutboxMessage message : batch) {
        if (rejected.test(message)) {
          failed.put(message.id(), new SendResult.Failure("InvalidParameter", true));
        } else if (unavailable.test(message)) {
          failed.put(message.id(), new SendResult.Failure("Throttled", false));
        } else {
          deliver(kind, destination, message);
          sent.add(message.id());
        }
      }
      lock.notifyAll();
      return new SendResult(sent, failed);
    }
  }

  private void deliver(DestinationKind kind, String destination, OutboxMessage message) {
    Map<String, String> attributes = Map.of("type", message.type(), "tenantid", String.valueOf(message.tenantId()));
    if (kind == DestinationKind.QUEUE) {
      enqueue(destination, new Entry(message.payload(), attributes));
      return;
    }
    List<Subscription> subscriptions = topics.get(destination);
    if (subscriptions == null) {
      throw new EventxException("no such topic: " + destination);
    }
    for (Subscription subscription : subscriptions) {
      if (subscription.types().isEmpty() || subscription.types().contains(message.type())) {
        enqueue(subscription.queue(), new Entry(message.payload(), attributes));
      }
    }
  }

  private void enqueue(String queueName, Entry entry) {
    Queue queue = queue(queueName);
    queue.entries.add(entry);
    queue.delivered++;
  }

  /** Puts a raw body straight into a queue, bypassing the outbox. For poison-message tests. */
  public void inject(String queue, String body, Map<String, String> attributes) {
    synchronized (lock) {
      enqueue(queue, new Entry(body, attributes));
      lock.notifyAll();
    }
  }

  @Override
  public List<ReceivedMessage> receive(String queueName, int max, Duration wait, Duration visibilityTimeout) {
    long deadline = System.nanoTime() + wait.toNanos();
    synchronized (lock) {
      while (true) {
        List<ReceivedMessage> out = take(queueName, max, visibilityTimeout);
        long remaining = deadline - System.nanoTime();
        if (!out.isEmpty() || remaining <= 0) {
          return out;
        }
        try {
          // Wake up regularly: a hidden message becomes visible without anyone notifying.
          lock.wait(Math.max(1, Math.min(5, remaining / 1_000_000)));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new EventxException("receive interrupted", e);
        }
      }
    }
  }

  private List<ReceivedMessage> take(String queueName, int max, Duration visibilityTimeout) {
    Queue queue = queue(queueName);
    long now = System.nanoTime();
    List<ReceivedMessage> out = new ArrayList<>();
    List<Entry> dead = new ArrayList<>();
    for (Entry entry : queue.entries) {
      if (out.size() >= max) {
        break;
      }
      if (entry.visibleAt > now) {
        continue;
      }
      if (entry.receiveCount >= queue.maxReceiveCount) {
        dead.add(entry);
        continue;
      }
      entry.receiveCount++;
      entry.visibleAt = now + visibilityTimeout.toNanos();
      entry.receipt = UUID.randomUUID().toString();
      out.add(new ReceivedMessage(entry.id, entry.receipt, entry.body, entry.attributes, entry.receiveCount));
    }
    if (!dead.isEmpty()) {
      queue.entries.removeAll(dead);
      Queue dlq = queue(queueName + "-dlq");
      for (Entry entry : dead) {
        dlq.entries.add(new Entry(entry.body, entry.attributes));
      }
    }
    return out;
  }

  @Override
  public void delete(String queueName, ReceivedMessage message) {
    synchronized (lock) {
      queue(queueName).entries.removeIf(e -> message.receiptHandle().equals(e.receipt));
    }
  }

  @Override
  public void changeVisibility(String queueName, ReceivedMessage message, Duration visibilityTimeout) {
    synchronized (lock) {
      for (Entry entry : queue(queueName).entries) {
        if (message.receiptHandle().equals(entry.receipt)) {
          entry.visibleAt = System.nanoTime() + visibilityTimeout.toNanos();
        }
      }
      lock.notifyAll();
    }
  }

  private Queue queue(String name) {
    Queue queue = queues.get(name);
    if (queue == null) {
      throw new EventxException("no such queue: " + name);
    }
    return queue;
  }
}
