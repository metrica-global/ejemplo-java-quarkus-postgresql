package com.zonacards.eventx.postgres;

import com.zonacards.dbx.Db;
import com.zonacards.eventx.Inbox;
import java.time.Duration;

/**
 * The inbox in PostgreSQL. {@code ON CONFLICT DO NOTHING} makes the check and the record one
 * atomic statement: if two tasks handle the same message at the same time, the second insert
 * waits for the first transaction and then reports "already handled", or proceeds if the first
 * one rolled back.
 */
public final class PostgresInbox implements Inbox {

  private final Db db;

  public PostgresInbox(Db db) {
    this.db = db;
  }

  @Override
  public boolean firstTime(String consumer, String messageId) {
    return db.update("INSERT INTO inbox (consumer, message_id) VALUES (?, ?) ON CONFLICT DO NOTHING", consumer, messageId) == 1;
  }

  @Override
  public int purge(Duration olderThan, int limit) {
    return db.update(
        """
        DELETE FROM inbox
         WHERE (consumer, message_id) IN (
               SELECT consumer, message_id
                 FROM inbox
                WHERE received_at < now() - make_interval(secs => ?)
                LIMIT ?)
        """,
        olderThan.toMillis() / 1000.0,
        limit);
  }
}
