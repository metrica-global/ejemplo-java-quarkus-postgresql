package com.zonacards.eventx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The handlers of one consumer, registered explicitly by message type. There is no classpath
 * scanning: what a service reacts to is readable in one place, its container.
 */
public final class HandlerRegistry {

  record Registration(MessageHandler handler, boolean transactional) {}

  private final Map<String, Registration> handlers = new LinkedHashMap<>();

  /**
   * Registers a handler that runs entirely inside one transaction, together with the
   * deduplication record. This is the default and the right choice for almost everything.
   */
  public HandlerRegistry on(String type, MessageHandler handler) {
    return register(type, new Registration(handler, true));
  }

  /**
   * Registers a handler that controls its own transaction through {@link MessageContext#once}.
   * For handlers whose expensive part (CPU, remote calls) must not hold a database connection.
   */
  public HandlerRegistry onSelfManaged(String type, MessageHandler handler) {
    return register(type, new Registration(handler, false));
  }

  private HandlerRegistry register(String type, Registration registration) {
    if (handlers.putIfAbsent(type, registration) != null) {
      throw new IllegalStateException("a handler is already registered for " + type);
    }
    return this;
  }

  Registration find(String type) {
    return handlers.get(type);
  }

  public Set<String> types() {
    return Set.copyOf(handlers.keySet());
  }
}
