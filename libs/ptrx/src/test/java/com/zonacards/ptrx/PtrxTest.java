package com.zonacards.ptrx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PtrxTest {

  @Test
  void valueOrKeepsTheValueUnlessItIsNull() {
    assertEquals("a", Ptrx.valueOr("a", "b"));
    assertEquals("b", Ptrx.valueOr(null, "b"));
  }

  @Test
  void coalesceReturnsTheFirstNonNull() {
    assertEquals(3, Ptrx.coalesce(null, 3, 4));
    assertNull(Ptrx.coalesce((Integer) null, null));
  }

  @Test
  void blankHandling() {
    assertEquals("x", Ptrx.firstNonBlank(null, " ", "x"));
    assertNull(Ptrx.firstNonBlank("", null));
    assertNull(Ptrx.blankToNull("  "));
    assertEquals("v", Ptrx.blankToNull(" v "));
  }
}
