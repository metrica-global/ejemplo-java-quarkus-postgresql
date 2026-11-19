package com.zonacards.eventx.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The canonical DDL of the outbox and inbox tables, for tests and for comparing migrations. */
public final class EventxSchema {

  private EventxSchema() {}

  public static String ddl() {
    try (InputStream in = EventxSchema.class.getResourceAsStream("schema.sql")) {
      if (in == null) {
        throw new IllegalStateException("schema.sql is missing from the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
