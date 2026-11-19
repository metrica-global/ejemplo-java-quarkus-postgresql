package com.zonacards.errx;

import java.util.Map;

/** Transport-neutral view of an error, ready to be serialised by the HTTP adapter. */
public record HttpErrorResponse(String code, String message, String type, int status, Map<String, Object> details) {}
