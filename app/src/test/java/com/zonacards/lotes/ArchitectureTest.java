package com.zonacards.lotes;

import com.zonacards.testkit.HexagonRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  @Test
  void theHexagonHolds() {
    HexagonRules.check("com.zonacards.lotes", "com.zonacards.giftcards..", "com.zonacards.app..");
  }
}
