package com.zonacards.testkit.fixture.good.container;

import com.zonacards.testkit.fixture.good.api.ThingEndpoint;
import com.zonacards.testkit.fixture.good.infra.MapThingRepository;
import com.zonacards.testkit.fixture.good.srv.ThingService;

public final class ThingContainer {
  public ThingEndpoint endpoint() {
    return new ThingEndpoint(new ThingService(new MapThingRepository()));
  }
}
