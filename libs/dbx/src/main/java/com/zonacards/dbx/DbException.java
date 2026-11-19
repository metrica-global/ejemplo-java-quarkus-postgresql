package com.zonacards.dbx;

import java.sql.SQLException;

/**
 * A database failure with its SQLState. Repositories translate the states that mean something to
 * the domain (a unique violation is a conflict) and let the rest propagate as an internal error.
 *
 * <p>Remember that PostgreSQL aborts the whole transaction on any error: a unique violation
 * cannot be caught and ignored mid-transaction. Where a duplicate is an expected outcome, write
 * {@code ON CONFLICT DO NOTHING} and look at the affected row count instead.
 */
public class DbException extends RuntimeException {

  public static final String UNIQUE_VIOLATION = "23505";
  public static final String FOREIGN_KEY_VIOLATION = "23503";
  public static final String NOT_NULL_VIOLATION = "23502";
  public static final String CHECK_VIOLATION = "23514";
  public static final String SERIALIZATION_FAILURE = "40001";
  public static final String DEADLOCK_DETECTED = "40P01";
  public static final String INSUFFICIENT_PRIVILEGE = "42501";
  public static final String NO_ACTIVE_TRANSACTION = "25P01";

  private final String sqlState;

  public DbException(String message, String sqlState) {
    super(message);
    this.sqlState = sqlState;
  }

  public DbException(SQLException cause) {
    super(cause.getMessage(), cause);
    this.sqlState = cause.getSQLState();
  }

  public String sqlState() {
    return sqlState;
  }

  public boolean isUniqueViolation() {
    return UNIQUE_VIOLATION.equals(sqlState);
  }

  /** True for a unique violation on the named constraint or index. */
  public boolean isUniqueViolation(String constraint) {
    return isUniqueViolation() && getMessage() != null && getMessage().contains("\"" + constraint + "\"");
  }

  public boolean isForeignKeyViolation() {
    return FOREIGN_KEY_VIOLATION.equals(sqlState);
  }

  public boolean isInsufficientPrivilege() {
    return INSUFFICIENT_PRIVILEGE.equals(sqlState);
  }

  /** Serialization failures, deadlocks and lost connections: safe to retry the whole transaction. */
  public boolean isTransient() {
    return SERIALIZATION_FAILURE.equals(sqlState)
        || DEADLOCK_DETECTED.equals(sqlState)
        || (sqlState != null && sqlState.startsWith("08"));
  }
}
