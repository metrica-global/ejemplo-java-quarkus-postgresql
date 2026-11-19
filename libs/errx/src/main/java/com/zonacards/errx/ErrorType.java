package com.zonacards.errx;

/** Category of an error. Each type carries the HTTP status suggested by default. */
public enum ErrorType {
  INTERNAL(500),
  VALIDATION(400),
  AUTHORIZATION(401),
  NOT_FOUND(404),
  CONFLICT(409),
  BUSINESS(422),
  EXTERNAL(502);

  private final int defaultHttpStatus;

  ErrorType(int defaultHttpStatus) {
    this.defaultHttpStatus = defaultHttpStatus;
  }

  public int defaultHttpStatus() {
    return defaultHttpStatus;
  }
}
