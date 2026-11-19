package com.zonacards.kernel;

import com.zonacards.errx.Errx;

public final class Validation {

  private Validation() {}

  /** Rejects a missing body and runs the request's own validation. Returns the request. */
  public static <T extends Validatable> T bindAndValidate(T request) {
    if (request == null) {
      throw Errx.validation("Invalid request body");
    }
    request.validate();
    return request;
  }

  /** Checks at the boundary that a path or body value is a UUID, and returns it in canonical form. */
  public static String uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw required(field);
    }
    try {
      return java.util.UUID.fromString(value.trim()).toString();
    } catch (IllegalArgumentException e) {
      throw invalid(field, "must be a UUID");
    }
  }

  public static Errx required(String field) {
    return Errx.validation(field + " is required").withDetail("field", field);
  }

  public static Errx invalid(String field, String reason) {
    return Errx.validation(field + " " + reason).withDetail("field", field);
  }
}
