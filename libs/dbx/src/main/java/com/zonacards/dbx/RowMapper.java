package com.zonacards.dbx;

import java.sql.SQLException;

@FunctionalInterface
public interface RowMapper<T> {

  T map(Row row) throws SQLException;
}
