package com.zonacards.dbx;

import com.zonacards.kernel.UnitOfWork;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * A unit of work over a single JDBC connection bound to the current thread. It is what the
 * services use outside Quarkus (library tests, command-line tools); inside Quarkus the unit of
 * work is backed by JTA instead, with the same contract:
 *
 * <ul>
 *   <li>join-or-begin: nested calls share the outer transaction;
 *   <li>any exception rolls back, and a failure inside a nested call dooms the outer one even
 *       if the caller swallows the exception;
 *   <li>strict: JDBC outside a unit of work is refused instead of silently auto-committing.
 * </ul>
 */
public final class LocalUnitOfWork implements UnitOfWork, ConnectionProvider {

  private static final class Tx {
    final Connection connection;
    boolean rollbackOnly;

    Tx(Connection connection) {
      this.connection = connection;
    }
  }

  private final DataSource dataSource;
  private final ThreadLocal<Tx> current = new ThreadLocal<>();

  public LocalUnitOfWork(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public <T> T inTx(Supplier<T> work) {
    Tx joined = current.get();
    if (joined != null) {
      try {
        return work.get();
      } catch (RuntimeException | Error e) {
        joined.rollbackOnly = true;
        throw e;
      }
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      Tx tx = new Tx(connection);
      current.set(tx);
      try {
        T result = work.get();
        if (tx.rollbackOnly) {
          connection.rollback();
          throw new DbException("transaction was marked rollback-only by a nested failure", "40000");
        }
        connection.commit();
        return result;
      } catch (RuntimeException | Error e) {
        rollbackQuietly(connection);
        throw e;
      } finally {
        current.remove();
      }
    } catch (SQLException e) {
      throw new DbException(e);
    }
  }

  @Override
  public Connection acquire() {
    Tx tx = current.get();
    if (tx == null) {
      throw new DbException("JDBC access outside a unit of work", DbException.NO_ACTIVE_TRANSACTION);
    }
    return tx.connection;
  }

  @Override
  public void release(Connection connection) {
    // The connection belongs to the unit of work and is closed when it ends.
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // The original failure is the one worth reporting.
    }
  }
}
