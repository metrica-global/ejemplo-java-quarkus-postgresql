package com.zonacards.eventx;

import com.zonacards.kernel.AuthContext;
import com.zonacards.kernel.TenantId;
import java.util.function.Predicate;

/** What a handler knows about the message it is handling. */
public final class MessageContext {

  private final EventEnvelope envelope;
  private final EventCodec codec;
  private final int receiveCount;
  private final Predicate<Runnable> once;
  private boolean duplicate;

  MessageContext(EventEnvelope envelope, EventCodec codec, int receiveCount, Predicate<Runnable> once) {
    this.envelope = envelope;
    this.codec = codec;
    this.receiveCount = receiveCount;
    this.once = once;
  }

  public EventEnvelope envelope() {
    return envelope;
  }

  public String type() {
    return envelope.type();
  }

  public TenantId tenantId() {
    return TenantId.of(envelope.tenantid());
  }

  public int receiveCount() {
    return receiveCount;
  }

  /** The payload, read as the consumer's own (tolerant) view of the event. */
  public <T> T data(Class<T> type) {
    return codec.data(envelope, type);
  }

  /**
   * The identity a handler acts with: the system, on behalf of the tenant that owns the event.
   * Nobody is logged in when an event arrives, but authorization and tenant scoping still apply.
   */
  public AuthContext systemAuth(String name, String... scopes) {
    return AuthContext.system(tenantId(), name, scopes);
  }

  /**
   * For self-managed handlers only: runs the work in one transaction together with the
   * deduplication record. Returns false, without running it, when the message had already been
   * handled. Everything before this call (the expensive part) runs outside any transaction.
   */
  public boolean once(Runnable transactionalWork) {
    if (once == null) {
      throw new IllegalStateException("once() is only for handlers registered with onSelfManaged()");
    }
    boolean ran = once.test(transactionalWork);
    if (!ran) {
      duplicate = true;
    }
    return ran;
  }

  boolean duplicate() {
    return duplicate;
  }
}
