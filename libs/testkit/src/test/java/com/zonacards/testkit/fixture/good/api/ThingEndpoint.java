package com.zonacards.testkit.fixture.good.api;

import com.zonacards.testkit.fixture.good.srv.ThingService;

public final class ThingEndpoint {
  private final ThingService service;

  public ThingEndpoint(ThingService service) {
    this.service = service;
  }

  public String show(String id) {
    return service.get(id).id();
  }
}
