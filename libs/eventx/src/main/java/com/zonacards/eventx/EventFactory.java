package com.zonacards.eventx;

import com.zonacards.kernel.Ids;
import com.zonacards.kernel.TenantId;
import java.time.Clock;

/** Builds envelopes for one producing service: fills id, time, source, correlation and trace. */
public final class EventFactory {

  private final String source;
  private final EventCodec codec;
  private final Clock clock;
  private final TraceContext trace;

  public EventFactory(String source, EventCodec codec, Clock clock, TraceContext trace) {
    this.source = source;
    this.codec = codec;
    this.clock = clock;
    this.trace = trace;
  }

  public EventFactory(String source, EventCodec codec) {
    this(source, codec, Clock.systemUTC(), TraceContext.NONE);
  }

  /**
   * @param type {@code zonacards.<domain>.<fact>.v<major>}
   * @param subject id of the aggregate the event is about
   * @param payload a record; it becomes the {@code data} of the envelope
   */
  public EventEnvelope create(String type, TenantId tenantId, String subject, Object payload) {
    return new EventEnvelope(
        EventEnvelope.SPEC_VERSION,
        Ids.uuidV7().toString(),
        source,
        type,
        subject,
        clock.instant(),
        EventEnvelope.CONTENT_TYPE,
        tenantId.value(),
        CorrelationContext.currentOrNew(),
        trace.currentTraceparent(),
        codec.toData(payload));
  }
}
