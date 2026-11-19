package com.zonacards.eventx.memory;

import com.zonacards.eventx.OutboxMessage;
import com.zonacards.eventx.OutboxStats;
import com.zonacards.eventx.OutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The outbox table as a map, with the same lease semantics as the PostgreSQL adapter. */
public final class InMemoryOutboxStore implements OutboxStore {

  private static final class Row {
    OutboxMessage message;
    Instant availableAt;
    Instant claimedUntil;
    Instant publishedAt;
    Instant parkedAt;
    int attempts;
    String lastError;
  }

  private final Map<UUID, Row> rows = new LinkedHashMap<>();
  private volatile Clock clock;

  public InMemoryOutboxStore() {
    this(Clock.systemUTC());
  }

  public InMemoryOutboxStore(Clock clock) {
    this.clock = clock;
  }

  /** Lets a test move time forward, for example past a lease. */
  public void setClock(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized void add(Collection<OutboxMessage> messages) {
    for (OutboxMessage message : messages) {
      Row row = new Row();
      row.message = message;
      row.availableAt = clock.instant();
      rows.put(message.id(), row);
      InMemoryUnitOfWork.onRollback(() -> remove(message.id()));
    }
  }

  private synchronized void remove(UUID id) {
    rows.remove(id);
  }

  @Override
  public synchronized List<OutboxMessage> claim(int limit, Duration lease) {
    Instant now = clock.instant();
    List<OutboxMessage> claimed = new ArrayList<>();
    for (Row row : rows.values()) {
      if (claimed.size() >= limit) {
        break;
      }
      boolean pending = row.publishedAt == null && row.parkedAt == null;
      boolean free = row.claimedUntil == null || row.claimedUntil.isBefore(now);
      if (pending && free && !row.availableAt.isAfter(now)) {
        row.claimedUntil = now.plus(lease);
        row.attempts++;
        OutboxMessage m = row.message;
        claimed.add(
            new OutboxMessage(m.id(), m.kind(), m.destination(), m.type(), m.tenantId(), m.payload(), row.attempts, m.createdAt()));
      }
    }
    return claimed;
  }

  @Override
  public synchronized void markPublished(Collection<UUID> ids) {
    for (UUID id : ids) {
      Row row = rows.get(id);
      if (row != null) {
        row.publishedAt = clock.instant();
        row.claimedUntil = null;
      }
    }
  }

  @Override
  public synchronized void reschedule(UUID id, String error, Duration delay) {
    Row row = rows.get(id);
    if (row != null) {
      row.claimedUntil = null;
      row.availableAt = clock.instant().plus(delay);
      row.lastError = error;
    }
  }

  @Override
  public synchronized void park(UUID id, String error) {
    Row row = rows.get(id);
    if (row != null) {
      row.claimedUntil = null;
      row.parkedAt = clock.instant();
      row.lastError = error;
    }
  }

  @Override
  public synchronized OutboxStats stats() {
    Instant now = clock.instant();
    long pending = 0;
    long parked = 0;
    Instant oldest = null;
    for (Row row : rows.values()) {
      if (row.parkedAt != null) {
        parked++;
      } else if (row.publishedAt == null) {
        pending++;
        if (oldest == null || row.message.createdAt().isBefore(oldest)) {
          oldest = row.message.createdAt();
        }
      }
    }
    return new OutboxStats(pending, oldest == null ? Duration.ZERO : Duration.between(oldest, now), parked);
  }

  @Override
  public synchronized int purgePublished(Duration olderThan, int limit) {
    Instant cutoff = clock.instant().minus(olderThan);
    List<UUID> old = new ArrayList<>();
    for (Row row : rows.values()) {
      if (old.size() < limit && row.publishedAt != null && row.publishedAt.isBefore(cutoff)) {
        old.add(row.message.id());
      }
    }
    old.forEach(rows::remove);
    return old.size();
  }

  public synchronized long publishedCount() {
    return rows.values().stream().filter(r -> r.publishedAt != null).count();
  }

  public synchronized String lastError(UUID id) {
    Row row = rows.get(id);
    return row == null ? null : row.lastError;
  }
}
