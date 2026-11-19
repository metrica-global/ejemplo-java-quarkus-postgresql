package com.zonacards.testkit.fixture.bad;

import com.zonacards.testkit.fixture.bad.infra.WidgetTable;

/** Violation: the domain reaches into its own infrastructure. */
public record Widget(String id) {
  public static Widget load(String id) {
    return new WidgetTable().read(id);
  }
}
