package com.zonacards.errx;

import java.util.Objects;

/** A registered, stable error code. The code string is part of the public contract. */
public record ErrorCode(String code, ErrorType type, int httpStatus, String message) {

  public ErrorCode {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(message, "message");
  }
}
