package com.zonacards.eventx;

import java.util.function.Supplier;

/** Wraps the handling of a message. The tracing adapter uses it to open a consumer span. */
public interface ConsumeInterceptor {

  ConsumeInterceptor NONE =
      new ConsumeInterceptor() {
        @Override
        public <T> T around(EventEnvelope envelope, String consumer, Supplier<T> next) {
          return next.get();
        }
      };

  <T> T around(EventEnvelope envelope, String consumer, Supplier<T> next);
}
