package com.zonacards.testkit.fixture.good.infra;

import com.zonacards.testkit.fixture.good.Thing;
import com.zonacards.testkit.fixture.good.ThingRepository;

public final class MapThingRepository implements ThingRepository {
  @Override
  public Thing get(String id) {
    return new Thing(id);
  }
}
