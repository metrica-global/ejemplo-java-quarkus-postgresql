package com.zonacards.giftcards;

import com.zonacards.testkit.HexagonRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  @Test
  void theHexagonHolds() {
    HexagonRules.check("com.zonacards.giftcards", "com.zonacards.lotes..");
  }
}
