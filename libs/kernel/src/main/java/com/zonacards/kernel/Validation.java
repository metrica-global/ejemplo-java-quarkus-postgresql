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

  public static Errx required(String field) {
    return Errx.validation(field + " is required").withDetail("field", field);
  }

  public static Errx invalid(String field, String reason) {
    return Errx.validation(field + " " + reason).withDetail("field", field);
  }
}
