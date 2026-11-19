package com.zonacards.server;

import com.zonacards.eventx.ConsumeInterceptor;
import com.zonacards.eventx.EventEnvelope;
import com.zonacards.eventx.TraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.function.Supplier;

/**
 * Carries the trace across the broker. When an event is written to the outbox the current span
 * is stored in its {@code traceparent}; when the event is consumed, possibly minutes later and
 * in another service, a CONSUMER span is opened as a child of it. One trace then shows the HTTP
 * request, the event and everything the other domain did because of it.
 */
@Singleton
public class OtelEventTracing implements TraceContext, ConsumeInterceptor {

  private static final TextMapGetter<EventEnvelope> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(EventEnvelope carrier) {
          return List.of("traceparent");
        }

        @Override
        public String get(EventEnvelope carrier, String key) {
          return carrier != null && "traceparent".equals(key) ? carrier.traceparent() : null;
        }
      };

  private final Tracer tracer;

  public OtelEventTracing(OpenTelemetry openTelemetry) {
    this.tracer = openTelemetry.getTracer("com.zonacards.eventx");
  }

  @Override
  public String currentTraceparent() {
    SpanContext context = Span.current().getSpanContext();
    if (!context.isValid()) {
      return null;
    }
    return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
  }

  @Override
  public <T> T around(EventEnvelope envelope, String consumer, Supplier<T> next) {
    Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), envelope, GETTER);
    Span span =
        tracer
            .spanBuilder(envelope.type() + " process")
            .setParent(parent)
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("messaging.system", "aws_sqs")
            .setAttribute("messaging.operation.type", "process")
            .setAttribute("messaging.message.id", envelope.id())
            .setAttribute("messaging.consumer.group.name", consumer)
            .setAttribute("zonacards.tenant_id", String.valueOf(envelope.tenantid()))
            .startSpan();
    try (Scope ignored = span.makeCurrent()) {
      return next.get();
    } catch (RuntimeException | Error e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      throw e;
    } finally {
      span.end();
    }
  }
}
