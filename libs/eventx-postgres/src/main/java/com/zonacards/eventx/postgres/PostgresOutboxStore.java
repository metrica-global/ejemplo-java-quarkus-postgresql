package com.zonacards.eventx.postgres;

import com.zonacards.dbx.Db;
import com.zonacards.dbx.Row;
import com.zonacards.dbx.SqlArray;
import com.zonacards.eventx.DestinationKind;
import com.zonacards.eventx.OutboxMessage;
import com.zonacards.eventx.OutboxStats;
import com.zonacards.eventx.OutboxStore;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The outbox in PostgreSQL. Table names are unqualified: they resolve to the schema of the
 * service through the role's {@code search_path}.
 */
public final class PostgresOutboxStore implements OutboxStore {

  private static final String INSERT =
      """
      INSERT INTO outbox (id, destination_kind, destination, message_type, tenant_id, payload, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      """;

  /**
   * The heart of the relay. {@code FOR UPDATE SKIP LOCKED} lets several relays claim at the same
   * time without waiting for each other and without ever taking the same row; the lease
   * ({@code claimed_until}) keeps the row theirs after the claiming transaction has committed,
   * while they talk to the broker. Time comes from the database, so the tasks need not agree on
   * a clock.
   */
  private static final String CLAIM =
      """
      UPDATE outbox o
         SET claimed_until = now() + make_interval(secs => ?),
             attempts = o.attempts + 1
       WHERE o.id IN (
             SELECT id
               FROM outbox
              WHERE published_at IS NULL
                AND parked_at IS NULL
                AND available_at <= now()
                AND (claimed_until IS NULL OR claimed_until < now())
              ORDER BY created_at
              LIMIT ?
                FOR UPDATE SKIP LOCKED)
      RETURNING o.id, o.destination_kind, o.destination, o.message_type, o.tenant_id, o.payload,
                o.attempts, o.created_at
      """;

  private final Db db;

  public PostgresOutboxStore(Db db) {
    this.db = db;
  }

  @Override
  public void add(Collection<OutboxMessage> messages) {
    List<Object[]> rows = new ArrayList<>(messages.size());
    for (OutboxMessage m : messages) {
      rows.add(new Object[] {m.id(), m.kind(), m.destination(), m.type(), m.tenantId(), m.payload(), m.createdAt()});
    }
    db.batch(INSERT, rows);
  }

  @Override
  public List<OutboxMessage> claim(int limit, Duration lease) {
    return db.queryList(CLAIM, PostgresOutboxStore::map, seconds(lease), limit);
  }

  @Override
  public void markPublished(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    db.update("UPDATE outbox SET published_at = now(), claimed_until = NULL WHERE id = ANY(?)", SqlArray.uuid(ids));
  }

  @Override
  public void reschedule(UUID id, String error, Duration delay) {
    db.update(
        "UPDATE outbox SET claimed_until = NULL, available_at = now() + make_interval(secs => ?), last_error = ?"
            + " WHERE id = ?",
        seconds(delay),
        truncate(error),
        id);
  }

  @Override
  public void park(UUID id, String error) {
    db.update("UPDATE outbox SET claimed_until = NULL, parked_at = now(), last_error = ? WHERE id = ?", truncate(error), id);
  }

  @Override
  public OutboxStats stats() {
    return db.queryOne(
            """
            SELECT count(*) FILTER (WHERE parked_at IS NULL) AS pending,
                   count(*) FILTER (WHERE parked_at IS NOT NULL) AS parked,
                   coalesce(extract(epoch FROM now() - min(created_at) FILTER (WHERE parked_at IS NULL)), 0)
                     AS oldest_seconds
              FROM outbox
             WHERE published_at IS NULL
            """,
            row ->
                new OutboxStats(
                    row.longValue("pending"),
                    Duration.ofMillis((long) (row.decimal("oldest_seconds").doubleValue() * 1000)),
                    row.longValue("parked")))
        .orElse(OutboxStats.EMPTY);
  }

  @Override
  public int purgePublished(Duration olderThan, int limit) {
    return db.update(
        """
        DELETE FROM outbox
         WHERE id IN (
               SELECT id
                 FROM outbox
                WHERE published_at IS NOT NULL
                  AND published_at < now() - make_interval(secs => ?)
                ORDER BY published_at
                LIMIT ?
                  FOR UPDATE SKIP LOCKED)
        """,
        seconds(olderThan),
        limit);
  }

  private static OutboxMessage map(Row row) throws SQLException {
    return new OutboxMessage(
        row.uuid("id"),
        row.enumValue("destination_kind", DestinationKind.class),
        row.string("destination"),
        row.string("message_type"),
        row.string("tenant_id"),
        row.string("payload"),
        row.integer("attempts"),
        row.instant("created_at"));
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }

  private static String truncate(String error) {
    return error == null || error.length() <= 500 ? error : error.substring(0, 500);
  }
}
