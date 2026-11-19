package com.zonacards.testkit.fixture.bad.srv;

import java.sql.Connection;

/** Violation: a service that knows JDBC, and another domain. */
public final class WidgetService {
  public boolean ready(Connection connection) {
    return connection != null && new com.zonacards.testkit.fixture.good.Thing("x").id() != null;
  }
}
