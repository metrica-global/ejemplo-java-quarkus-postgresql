package com.zonacards.logx;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Structured logging. Fields are attached to the log record through the MDC, so the JSON
 * formatter emits them as real attributes instead of text glued to the message:
 *
 * <pre>{@code
 * private static final Logx LOG = Logx.get(LoteService.class);
 * LOG.with("lote_id", id).with("tenant_id", tenant).info("lote solicitado");
 * LOG.withError(e).with("queue", queue).error("receive failed");
 * }</pre>
 *
 * Never put secrets, card codes or CVVs in a field: logs are not a vault.
 */
public final class Logx {

  private final Logger logger;

  private Logx(Logger logger) {
    this.logger = logger;
  }

  public static Logx get(Class<?> type) {
    return new Logx(Logger.getLogger(type));
  }

  public Entry with(String key, Object value) {
    return new Entry().with(key, value);
  }

  public Entry withError(Throwable error) {
    return new Entry().withError(error);
  }

  public void debug(String message) {
    emit(Logger.Level.DEBUG, message, Map.of(), null);
  }

  public void info(String message) {
    emit(Logger.Level.INFO, message, Map.of(), null);
  }

  public void warn(String message) {
    emit(Logger.Level.WARN, message, Map.of(), null);
  }

  public void error(String message) {
    emit(Logger.Level.ERROR, message, Map.of(), null);
  }

  private void emit(Logger.Level level, String message, Map<String, Object> fields, Throwable error) {
    if (!logger.isEnabled(level)) {
      return;
    }
    Map<String, Object> previous = new LinkedHashMap<>();
    for (Map.Entry<String, Object> field : fields.entrySet()) {
      previous.put(field.getKey(), MDC.put(field.getKey(), String.valueOf(field.getValue())));
    }
    try {
      logger.log(level, message, error);
    } finally {
      for (Map.Entry<String, Object> field : previous.entrySet()) {
        if (field.getValue() == null) {
          MDC.remove(field.getKey());
        } else {
          MDC.put(field.getKey(), field.getValue());
        }
      }
    }
  }

  /** A log line being built. Not thread-safe; build it and emit it in one expression. */
  public final class Entry {

    private final Map<String, Object> fields = new LinkedHashMap<>();
    private Throwable error;

    private Entry() {}

    public Entry with(String key, Object value) {
      if (key != null && !key.isBlank() && value != null) {
        fields.put(key, value);
      }
      return this;
    }

    public Entry withError(Throwable error) {
      this.error = error;
      return this;
    }

    public void debug(String message) {
      emit(Logger.Level.DEBUG, message, fields, error);
    }

    public void info(String message) {
      emit(Logger.Level.INFO, message, fields, error);
    }

    public void warn(String message) {
      emit(Logger.Level.WARN, message, fields, error);
    }

    public void error(String message) {
      emit(Logger.Level.ERROR, message, fields, error);
    }
  }
}
