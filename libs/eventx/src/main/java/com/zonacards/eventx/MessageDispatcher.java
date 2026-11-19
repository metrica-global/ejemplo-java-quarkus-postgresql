package com.zonacards.eventx;

import com.zonacards.kernel.UnitOfWork;

/**
 * Turns a delivered message into at most one execution of its handler. This is the idempotent
 * consumer: the deduplication record and the handler's effects commit together, so a message
 * delivered twice changes the database once.
 *
 * <p>It knows nothing about queues, which is why tests can feed it envelopes in any order and
 * as many times as they like.
 */
public final class MessageDispatcher {

  private final String consumer;
  private final HandlerRegistry registry;
  private final Inbox inbox;
  private final UnitOfWork uow;
  private final EventCodec codec;
  private final ConsumeInterceptor interceptor;

  public MessageDispatcher(
      String consumer,
      HandlerRegistry registry,
      Inbox inbox,
      UnitOfWork uow,
      EventCodec codec,
      ConsumeInterceptor interceptor) {
    this.consumer = consumer;
    this.registry = registry;
    this.inbox = inbox;
    this.uow = uow;
    this.codec = codec;
    this.interceptor = interceptor;
  }

  public String consumer() {
    return consumer;
  }

  public Outcome dispatch(ReceivedMessage message) {
    return dispatch(codec.decode(message.body()), message.receiveCount());
  }

  public Outcome dispatch(EventEnvelope envelope, int receiveCount) {
    HandlerRegistry.Registration registration = registry.find(envelope.type());
    if (registration == null) {
      return Outcome.IGNORED;
    }
    return interceptor.around(envelope, consumer, () -> run(registration, envelope, receiveCount));
  }

  private Outcome run(HandlerRegistry.Registration registration, EventEnvelope envelope, int receiveCount) {
    String previous = CorrelationContext.set(envelope.correlationid());
    try {
      if (registration.transactional()) {
        MessageContext context = new MessageContext(envelope, codec, receiveCount, null);
        return uow.inTx(
            () -> {
              if (!inbox.firstTime(consumer, envelope.id())) {
                return Outcome.DUPLICATE;
              }
              registration.handler().handle(context);
              return Outcome.HANDLED;
            });
      }
      MessageContext context =
          new MessageContext(
              envelope,
              codec,
              receiveCount,
              work ->
                  uow.inTx(
                      () -> {
                        if (!inbox.firstTime(consumer, envelope.id())) {
                          return false;
                        }
                        work.run();
                        return true;
                      }));
      registration.handler().handle(context);
      return context.duplicate() ? Outcome.DUPLICATE : Outcome.HANDLED;
    } finally {
      CorrelationContext.restore(previous);
    }
  }
}
