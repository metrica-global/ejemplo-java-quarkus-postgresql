package com.zonacards.errx;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error codes of one module. Every domain declares its own registry with a prefix, so codes are
 * unique across the system and can be grepped: {@code new ErrorRegistry("LOTE")} registers
 * {@code LOTE_NOT_FOUND}, {@code LOTE_ALREADY_ANNULLED}, and so on.
 */
public final class ErrorRegistry {

  private final String prefix;
  private final Map<String, ErrorCode> codes = new LinkedHashMap<>();

  public ErrorRegistry(String prefix) {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("prefix is required");
    }
    this.prefix = prefix;
  }

  public String prefix() {
    return prefix;
  }

  /** Registers {@code <prefix>_<code>}. Registering the same code twice is a programming error. */
  public synchronized ErrorCode register(String code, ErrorType type, int httpStatus, String message) {
    String full = prefix + "_" + code;
    if (codes.containsKey(full)) {
      throw new IllegalStateException("error code already registered: " + full);
    }
    ErrorCode errorCode = new ErrorCode(full, type, httpStatus, message);
    codes.put(full, errorCode);
    return errorCode;
  }

  /** Registers a code using the default HTTP status of its type. */
  public ErrorCode register(String code, ErrorType type, String message) {
    return register(code, type, type.defaultHttpStatus(), message);
  }

  public Errx create(ErrorCode code) {
    return new Errx(code.code(), code.message(), code.type(), code.httpStatus(), null);
  }

  public Errx createWithMessage(ErrorCode code, String message) {
    return new Errx(code.code(), message, code.type(), code.httpStatus(), null);
  }

  public Errx createWithCause(ErrorCode code, Throwable cause) {
    return new Errx(code.code(), code.message(), code.type(), code.httpStatus(), cause);
  }

  public synchronized Map<String, ErrorCode> codes() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(codes));
  }
}
