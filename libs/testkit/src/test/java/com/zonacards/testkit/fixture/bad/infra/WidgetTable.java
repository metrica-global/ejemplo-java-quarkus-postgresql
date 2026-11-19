package com.zonacards.testkit.fixture.bad.infra;

import com.zonacards.testkit.fixture.bad.Widget;

public final class WidgetTable {
  public Widget read(String id) {
    return new Widget(id);
  }
}
