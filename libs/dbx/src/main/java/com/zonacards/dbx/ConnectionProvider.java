package com.zonacards.dbx;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Where {@link Db} gets its connection for one statement. With a transaction-aware pool (Agroal
 * under JTA) "acquire" returns the connection enlisted in the current transaction and "release"
 * hands it back to the pool only when the transaction ends.
 */
public interface ConnectionProvider {

  Connection acquire() throws SQLException;

  void release(Connection connection) throws SQLException;

  static ConnectionProvider of(DataSource dataSource) {
    return new ConnectionProvider() {
      @Override
      public Connection acquire() throws SQLException {
        return dataSource.getConnection();
      }

      @Override
      public void release(Connection connection) throws SQLException {
        connection.close();
      }
    };
  }
}
