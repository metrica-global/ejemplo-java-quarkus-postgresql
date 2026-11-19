package com.zonacards.testkit.fixture.bad.api;

import com.zonacards.testkit.fixture.bad.infra.WidgetTable;

/** Violation: the API skips the service and goes straight to the adapter. */
public final class WidgetEndpoint {
  public String show(String id) {
    return new WidgetTable().read(id).id();
  }
}
