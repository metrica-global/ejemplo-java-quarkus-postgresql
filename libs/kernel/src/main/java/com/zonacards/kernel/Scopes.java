package com.zonacards.kernel;

import java.util.Collection;

/**
 * Scope matching. A held scope grants a required one when they are equal, when the held scope is
 * the tenant wildcard {@code *}, or when it is a prefix wildcard ({@code lotes:*} grants
 * {@code lotes:read}). Scopes under {@code platform:} are inert: they never match, not even
 * exactly, so tenant credentials cannot carry platform authority.
 */
public final class Scopes {

  private static final String PLATFORM_PREFIX = "platform:";

  private Scopes() {}

  public static boolean match(String held, String required) {
    if (held == null || required == null || held.isBlank() || required.isBlank()) {
      return false;
    }
    if (held.startsWith(PLATFORM_PREFIX) || required.startsWith(PLATFORM_PREFIX)) {
      return false;
    }
    if (held.equals(required) || held.equals("*")) {
      return true;
    }
    if (held.length() > 2 && held.endsWith(":*")) {
      String prefix = held.substring(0, held.length() - 2);
      return required.length() > prefix.length()
          && required.startsWith(prefix)
          && required.charAt(prefix.length()) == ':';
    }
    return false;
  }

  public static boolean contain(Collection<String> held, String required) {
    if (held == null) {
      return false;
    }
    for (String scope : held) {
      if (match(scope, required)) {
        return true;
      }
    }
    return false;
  }
}
