package com.zonacards.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

  @Test
  void uuidV7HasTheRightVersionVariantAndTimestamp() {
    long now = 1_795_000_000_000L;
    UUID id = Ids.uuidV7(now);
    assertEquals(7, id.version());
    assertEquals(2, id.variant());
    assertEquals(now, id.getMostSignificantBits() >>> 16);
  }

  @Test
  void idsSortByCreationTimeAndDoNotRepeat() {
    assertTrue(Ids.uuidV7(1_000).toString().compareTo(Ids.uuidV7(2_000).toString()) < 0);
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      assertTrue(seen.add(Ids.uuidV7()));
    }
    assertEquals(36, LoteId.newId().value().length());
  }
}
