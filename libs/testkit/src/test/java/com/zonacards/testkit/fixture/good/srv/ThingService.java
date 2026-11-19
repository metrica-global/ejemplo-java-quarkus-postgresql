package com.zonacards.testkit.fixture.good.srv;

import com.zonacards.testkit.fixture.good.Thing;
import com.zonacards.testkit.fixture.good.ThingRepository;

public final class ThingService {
  private final ThingRepository repository;

  public ThingService(ThingRepository repository) {
    this.repository = repository;
  }

  public Thing get(String id) {
    return repository.get(id);
  }
}
