package com.zonacards.dbx;

import java.util.Collection;

/**
 * An array parameter, for set-based statements: {@code WHERE id = ANY(?)} or a bulk insert with
 * {@code SELECT * FROM unnest(?, ?, ?)}. One round trip instead of one statement per row.
 */
public record SqlArray(String sqlType, Object[] values) {

  public static SqlArray text(Collection<String> values) {
    return new SqlArray("text", values.toArray(String[]::new));
  }

  public static SqlArray uuid(Collection<java.util.UUID> values) {
    return new SqlArray("uuid", values.toArray(java.util.UUID[]::new));
  }

  public static SqlArray integers(Collection<Integer> values) {
    return new SqlArray("int4", values.toArray(Integer[]::new));
  }

  /** The driver only accepts binary arrays as {@code byte[][]}, not as a generic array of {@code byte[]}. */
  public static SqlArray bytes(Collection<byte[]> values) {
    return new SqlArray("bytea", values.toArray(byte[][]::new));
  }
}
