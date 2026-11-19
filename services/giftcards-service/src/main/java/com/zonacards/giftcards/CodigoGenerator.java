package com.zonacards.giftcards;

import java.util.random.RandomGenerator;

/**
 * Generates the secrets of a gift card.
 *
 * <p>A code has 16 digits: the 3 digits of the ISO 3166-1 numeric country code, 12 random digits
 * and a Luhn check digit. The check digit lets a point of sale reject a mistyped code without
 * asking the server. The random part must come from a cryptographically secure generator:
 * a predictable code is money anyone can claim.
 */
public final class CodigoGenerator {

  public static final int LONGITUD = 16;
  private static final int ALEATORIOS = 12;
  private static final int CVV_LONGITUD = 3;

  private CodigoGenerator() {}

  /** @param random a CSPRNG ({@link java.security.SecureRandom}); tests may pass a seeded one */
  public static String codigo(String pais, RandomGenerator random) {
    if (pais == null || !pais.matches("\\d{3}")) {
      throw new IllegalArgumentException("pais must be an ISO 3166-1 numeric code");
    }
    StringBuilder digits = new StringBuilder(LONGITUD).append(pais);
    for (int i = 0; i < ALEATORIOS; i++) {
      digits.append((char) ('0' + random.nextInt(10)));
    }
    return digits.append(luhnCheckDigit(digits)).toString();
  }

  public static String cvv(RandomGenerator random) {
    StringBuilder digits = new StringBuilder(CVV_LONGITUD);
    for (int i = 0; i < CVV_LONGITUD; i++) {
      digits.append((char) ('0' + random.nextInt(10)));
    }
    return digits.toString();
  }

  /** True for a string of 16 digits whose last digit is the Luhn check digit of the rest. */
  public static boolean esValido(String codigo) {
    if (codigo == null || !codigo.matches("\\d{" + LONGITUD + "}")) {
      return false;
    }
    return luhnCheckDigit(codigo.subSequence(0, LONGITUD - 1)) == codigo.charAt(LONGITUD - 1);
  }

  /** What may be shown and logged: the country prefix and the last four digits. */
  public static String mascara(String codigo) {
    return codigo.substring(0, 3) + "*".repeat(LONGITUD - 7) + codigo.substring(LONGITUD - 4);
  }

  static char luhnCheckDigit(CharSequence payload) {
    int sum = 0;
    boolean doubled = true;
    for (int i = payload.length() - 1; i >= 0; i--) {
      int digit = payload.charAt(i) - '0';
      if (doubled) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubled = !doubled;
    }
    return (char) ('0' + (10 - sum % 10) % 10);
  }
}
