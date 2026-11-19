package com.zonacards.logx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogxTest {

  private final List<ExtLogRecord> records = new ArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          ExtLogRecord ext = (ExtLogRecord) record;
          ext.copyMdc();
          records.add(ext);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void attach() {
    Logger.getLogger("").addHandler(capture);
  }

  @AfterEach
  void detach() {
    Logger.getLogger("").removeHandler(capture);
    MDC.clear();
  }

  @Test
  void fieldsTravelAsMdcAttributesAndAreRemovedAfterwards() {
    Logx.get(LogxTest.class).with("lote_id", "L-1").with("cantidad", 200).info("lote solicitado");

    assertEquals(1, records.size());
    ExtLogRecord record = records.get(0);
    assertEquals("lote solicitado", record.getMessage());
    assertEquals("L-1", record.getMdc("lote_id"));
    assertEquals("200", record.getMdc("cantidad"));
    assertNull(MDC.get("lote_id"), "fields must not leak into later log lines");
  }

  @Test
  void aFieldThatShadowsTheRequestContextIsRestored() {
    MDC.put("request_id", "req-1");
    Logx.get(LogxTest.class).with("request_id", "inner").warn("shadowed");
    assertEquals("inner", records.get(0).getMdc("request_id"));
    assertEquals("req-1", MDC.get("request_id"));
  }

  @Test
  void errorsAreAttachedAndNullFieldsAreIgnored() {
    IllegalStateException boom = new IllegalStateException("boom");
    Logx.get(LogxTest.class).withError(boom).with("skipped", null).error("failed");
    assertSame(boom, records.get(0).getThrown());
    assertTrue(records.get(0).getMdcCopy().isEmpty());
  }
}
