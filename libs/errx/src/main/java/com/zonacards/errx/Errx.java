package com.zonacards.errx;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The application error. It crosses every layer unchanged: domain and services throw it,
 * infrastructure translates driver failures into it, and the HTTP boundary renders it.
 *
 * <p>Two errors are the same error when they share the {@link #code()}, regardless of instance,
 * message or details. Compare with {@link #is(Errx)} or {@link #hasCode(ErrorCode)}.
 */
public final class Errx extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final ErrorType type;
  private final int httpStatus;
  private final transient Map<String, Object> details = new LinkedHashMap<>();

  Errx(String code, String message, ErrorType type, int httpStatus, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.type = Objects.requireNonNull(type, "type");
    this.httpStatus = httpStatus;
  }

  // ---- constructors for errors that do not need a registered code ----

  public static Errx of(ErrorType type, String message) {
    return new Errx(type.name(), message, type, type.defaultHttpStatus(), null);
  }

  public static Errx internal(String message) {
    return of(ErrorType.INTERNAL, message);
  }

  public static Errx validation(String message) {
    return of(ErrorType.VALIDATION, message);
  }

  public static Errx notFound(String message) {
    return of(ErrorType.NOT_FOUND, message);
  }

  public static Errx unauthorized(String message) {
    return of(ErrorType.AUTHORIZATION, message);
  }

  public static Errx conflict(String message) {
    return of(ErrorType.CONFLICT, message);
  }

  public static Errx business(String message) {
    return of(ErrorType.BUSINESS, message);
  }

  public static Errx external(String message) {
    return of(ErrorType.EXTERNAL, message);
  }

  /**
   * Adds context to a failure. If the cause is already an {@code Errx} its code, type, status and
   * details are preserved, so wrapping never hides a domain error behind a generic one.
   */
  public static Errx wrap(Throwable cause, String message, ErrorType type) {
    Objects.requireNonNull(cause, "cause");
    if (cause instanceof Errx existing) {
      Errx wrapped = new Errx(existing.code, message, existing.type, existing.httpStatus, cause);
      wrapped.details.putAll(existing.details);
      return wrapped;
    }
    return new Errx(type.name(), message, type, type.defaultHttpStatus(), cause);
  }

  // ---- accessors ----

  public String code() {
    return code;
  }

  public ErrorType type() {
    return type;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public Map<String, Object> details() {
    return Collections.unmodifiableMap(details);
  }

  public Errx withDetail(String key, Object value) {
    details.put(key, value);
    return this;
  }

  public Errx withDetails(Map<String, ?> more) {
    details.putAll(more);
    return this;
  }

  // ---- comparison ----

  public boolean is(Errx other) {
    return other != null && code.equals(other.code);
  }

  public boolean hasCode(ErrorCode errorCode) {
    return errorCode != null && code.equals(errorCode.code());
  }

  /** True if any error in the cause chain is an {@code Errx} with the given code. */
  public static boolean is(Throwable error, ErrorCode errorCode) {
    Errx found = find(error);
    return found != null && found.hasCode(errorCode);
  }

  /** True if any error in the cause chain is an {@code Errx} of type NOT_FOUND. */
  public static boolean isNotFound(Throwable error) {
    Errx found = find(error);
    return found != null && found.type == ErrorType.NOT_FOUND;
  }

  /** First {@code Errx} in the cause chain, or null. */
  public static Errx find(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth++ < 32) {
      if (current instanceof Errx errx) {
        return errx;
      }
      current = current.getCause();
    }
    return null;
  }

  public HttpErrorResponse toHttpResponse() {
    return new HttpErrorResponse(code, getMessage(), type.name(), httpStatus, details());
  }

  @Override
  public String toString() {
    String base = "[" + code + "] " + getMessage();
    return getCause() == null ? base : base + ": " + getCause();
  }
}
