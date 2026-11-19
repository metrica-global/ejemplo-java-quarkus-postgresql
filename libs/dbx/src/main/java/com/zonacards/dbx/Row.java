package com.zonacards.dbx;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** The current row of a result set, with null-safe typed accessors. */
public final class Row {

  private final ResultSet rs;

  Row(ResultSet rs) {
    this.rs = rs;
  }

  public String string(String column) throws SQLException {
    return rs.getString(column);
  }

  public int integer(String column) throws SQLException {
    return rs.getInt(column);
  }

  public Integer integerOrNull(String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  public long longValue(String column) throws SQLException {
    return rs.getLong(column);
  }

  public boolean bool(String column) throws SQLException {
    return rs.getBoolean(column);
  }

  public BigDecimal decimal(String column) throws SQLException {
    return rs.getBigDecimal(column);
  }

  public byte[] bytes(String column) throws SQLException {
    return rs.getBytes(column);
  }

  public UUID uuid(String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  /** A {@code timestamptz} column as an instant, or null. */
  public Instant instant(String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public LocalDate localDate(String column) throws SQLException {
    return rs.getObject(column, LocalDate.class);
  }

  public <E extends Enum<E>> E enumValue(String column, Class<E> type) throws SQLException {
    String value = rs.getString(column);
    return value == null ? null : Enum.valueOf(type, value);
  }

  public ResultSet unwrap() {
    return rs;
  }
}
