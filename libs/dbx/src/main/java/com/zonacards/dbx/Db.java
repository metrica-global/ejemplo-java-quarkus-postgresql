package com.zonacards.dbx;

import com.zonacards.kernel.TypedId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Plain JDBC without the ceremony: explicit SQL, positional parameters, a mapper per row. There
 * is no ORM on purpose. The statements that make this system correct ({@code FOR UPDATE SKIP
 * LOCKED}, {@code ON CONFLICT DO NOTHING}, set-based inserts) are written by hand and stay
 * visible in the repository that owns them.
 *
 * <p>Transactions are not started here. Every call runs on the connection of the unit of work
 * that surrounds it; outside one, the connection provider refuses.
 */
public final class Db {

  private final ConnectionProvider connections;

  public Db(ConnectionProvider connections) {
    this.connections = connections;
  }

  public Db(DataSource dataSource) {
    this(ConnectionProvider.of(dataSource));
  }

  public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
    return execute(
        sql,
        params,
        statement -> {
          try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.ofNullable(mapper.map(new Row(rs))) : Optional.<T>empty();
          }
        });
  }

  public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
    return execute(
        sql,
        params,
        statement -> {
          try (ResultSet rs = statement.executeQuery()) {
            List<T> out = new ArrayList<>();
            Row row = new Row(rs);
            while (rs.next()) {
              out.add(mapper.map(row));
            }
            return out;
          }
        });
  }

  /** First column of the first row as a long; 0 when there is no row. For counts. */
  public long queryLong(String sql, Object... params) {
    return execute(
        sql,
        params,
        statement -> {
          try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
          }
        });
  }

  /** Runs an INSERT, UPDATE or DELETE and returns the number of affected rows. */
  public int update(String sql, Object... params) {
    return execute(sql, params, PreparedStatement::executeUpdate);
  }

  /** Runs the same statement once per row of parameters, in one JDBC batch. */
  public int[] batch(String sql, List<Object[]> rows) {
    if (rows.isEmpty()) {
      return new int[0];
    }
    Connection connection = acquire();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (Object[] row : rows) {
        bind(connection, statement, row);
        statement.addBatch();
      }
      return statement.executeBatch();
    } catch (SQLException e) {
      throw new DbException(e);
    } finally {
      release(connection);
    }
  }

  @FunctionalInterface
  private interface StatementWork<T> {
    T run(PreparedStatement statement) throws SQLException;
  }

  private <T> T execute(String sql, Object[] params, StatementWork<T> work) {
    Connection connection = acquire();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(connection, statement, params);
      return work.run(statement);
    } catch (SQLException e) {
      throw new DbException(e);
    } finally {
      release(connection);
    }
  }

  private Connection acquire() {
    try {
      return connections.acquire();
    } catch (SQLException e) {
      throw new DbException(e);
    }
  }

  private void release(Connection connection) {
    try {
      connections.release(connection);
    } catch (SQLException e) {
      // The statement already succeeded or failed on its own; a failed release must not hide it.
    }
  }

  private static void bind(Connection connection, PreparedStatement statement, Object[] params)
      throws SQLException {
    if (params == null) {
      return;
    }
    for (int i = 0; i < params.length; i++) {
      int index = i + 1;
      Object param = params[i];
      switch (param) {
        case null -> statement.setObject(index, null);
        case Instant instant -> statement.setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        case Enum<?> value -> statement.setString(index, value.name());
        // Sent untyped so the server infers the column type: the same id binds to uuid or text.
        case TypedId id -> statement.setObject(index, id.value(), Types.OTHER);
        case SqlArray array -> statement.setArray(index, connection.createArrayOf(array.sqlType(), array.values()));
        default -> statement.setObject(index, param);
      }
    }
  }
}
