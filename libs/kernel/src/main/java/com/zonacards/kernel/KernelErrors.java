package com.zonacards.kernel;

import com.zonacards.errx.ErrorCode;
import com.zonacards.errx.ErrorRegistry;
import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;

/** Errors shared by every module: authentication, authorization and concurrency. */
public final class KernelErrors {

  public static final ErrorRegistry REGISTRY = new ErrorRegistry("AUTH");

  public static final ErrorCode UNAUTHORIZED =
      REGISTRY.register("UNAUTHORIZED", ErrorType.AUTHORIZATION, 401, "Authentication is required");
  public static final ErrorCode ACCESS_DENIED =
      REGISTRY.register("ACCESS_DENIED", ErrorType.AUTHORIZATION, 403, "Access denied");

  private KernelErrors() {}

  public static Errx unauthorized() {
    return REGISTRY.create(UNAUTHORIZED);
  }

  public static Errx accessDenied() {
    return REGISTRY.create(ACCESS_DENIED);
  }
}
