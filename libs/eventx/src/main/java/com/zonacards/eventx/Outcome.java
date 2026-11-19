package com.zonacards.eventx;

/** What happened to a consumed message. Every value except FAILED removes it from the queue. */
public enum Outcome {
  /** The handler ran and its effects were committed. */
  HANDLED,
  /** Already handled before; acknowledged without running the handler. */
  DUPLICATE,
  /** No handler for this type; acknowledged. Filters should prevent it, a consumer must survive it. */
  IGNORED,
  /** The handler threw; the message stays in the queue and will be redelivered. */
  FAILED
}
