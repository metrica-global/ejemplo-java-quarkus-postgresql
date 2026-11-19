package com.zonacards.eventx;

/** Port to the tracing system: gives the W3C {@code traceparent} of the current span, if any. */
@FunctionalInterface
public interface TraceContext {

  TraceContext NONE = () -> null;

  String currentTraceparent();
}
