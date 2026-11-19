package com.zonacards.giftcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodigoGeneratorTest {

  @Test
  void theCheckDigitIsLuhn() {
    // Reference values of the Luhn algorithm (ISO/IEC 7812-1).
    assertEquals('3', CodigoGenerator.luhnCheckDigit("7992739871"));
    assertEquals('6', CodigoGenerator.luhnCheckDigit("453957876362148"));
    assertTrue(CodigoGenerator.esValido("4539578763621486"));
    assertFalse(CodigoGenerator.esValido("4539578763621487"));
  }

  @Test
  void aCodeHasTheCountryTwelveRandomDigitsAndAValidCheckDigit() {
    SecureRandom random = new SecureRandom();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 20_000; i++) {
      String codigo = CodigoGenerator.codigo("604", random);
      assertEquals(16, codigo.length());
      assertTrue(codigo.startsWith("604"));
      assertTrue(CodigoGenerator.esValido(codigo), codigo);
      seen.add(codigo);
    }
    assertEquals(20_000, seen.size(), "20 000 codes out of 10^12 should not repeat");
  }

  @Test
  void aSingleWrongDigitIsAlwaysDetected() {
    String codigo = CodigoGenerator.codigo("218", new Random(42));
    for (int position = 0; position < codigo.length(); position++) {
      for (char digit = '0'; digit <= '9'; digit++) {
        if (digit == codigo.charAt(position)) {
          continue;
        }
        String wrong = codigo.substring(0, position) + digit + codigo.substring(position + 1);
        assertFalse(CodigoGenerator.esValido(wrong), wrong);
      }
    }
  }

  @Test
  void malformedInputIsRejected() {
    assertFalse(CodigoGenerator.esValido(null));
    assertFalse(CodigoGenerator.esValido("123"));
    assertFalse(CodigoGenerator.esValido("60412345678901AB"));
    assertThrows(IllegalArgumentException.class, () -> CodigoGenerator.codigo("PE", new Random()));
    assertThrows(IllegalArgumentException.class, () -> CodigoGenerator.codigo(null, new Random()));
  }

  @Test
  void theCvvHasThreeDigitsAndTheMaskHidesTheMiddle() {
    Random random = new Random(1);
    for (int i = 0; i < 100; i++) {
      assertTrue(CodigoGenerator.cvv(random).matches("\\d{3}"));
    }
    assertEquals("604*********4321", CodigoGenerator.mascara("6041234567894321"));
  }
}
