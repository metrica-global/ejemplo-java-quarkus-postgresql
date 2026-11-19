package com.zonacards.eventx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventCodecTest {

  private final EventCodec codec = new EventCodec();

  @Test
  void anEnvelopeIsACloudEventsDocumentAndRoundTrips() {
    String previous = CorrelationContext.set("corr-1");
    EventEnvelope event;
    try {
      event = Fixtures.event("zonacards.test.created.v1", 7);
    } finally {
      CorrelationContext.restore(previous);
    }
    String json = codec.encode(event);

    assertTrue(json.contains("\"specversion\":\"1.0\""), json);
    assertTrue(json.contains("\"type\":\"zonacards.test.created.v1\""), json);
    assertTrue(json.contains("\"tenantid\":\"pe\""), json);
    assertTrue(json.contains("\"correlationid\":\"corr-1\""), json);
    assertTrue(json.matches(".*\"time\":\"\\d{4}-\\d{2}-\\d{2}T.*Z\".*"), "time must be an ISO-8601 string: " + json);
    assertTrue(!json.contains("traceparent"), "absent attributes are omitted, not written as null");

    EventEnvelope decoded = codec.decode(json);
    assertEquals(event, decoded);
    assertEquals(new Fixtures.Payload("p7", 7), codec.data(decoded, Fixtures.Payload.class));
  }

  @Test
  void amountsAndDatesCrossTheWireExactly() {
    record Money(java.math.BigDecimal monto, java.time.LocalDate vigenciaHasta) {}
    Money original = new Money(new java.math.BigDecimal("50.00"), java.time.LocalDate.parse("2027-12-31"));
    EventEnvelope event = Fixtures.FACTORY.create("zonacards.test.money.v1", Fixtures.TENANT, "s", original);

    String json = codec.encode(event);
    assertTrue(json.contains("\"monto\":50.00"), "an amount is a plain decimal with its scale: " + json);
    assertTrue(json.contains("\"vigenciaHasta\":\"2027-12-31\""), json);
    assertEquals(original, codec.data(codec.decode(json), Money.class));

    Money large = new Money(new java.math.BigDecimal("1234567.10"), java.time.LocalDate.parse("2027-01-01"));
    String largeJson = codec.encode(Fixtures.FACTORY.create("zonacards.test.money.v1", Fixtures.TENANT, "s", large));
    assertTrue(largeJson.contains("\"monto\":1234567.10"), largeJson);
    assertEquals(large, codec.data(codec.decode(largeJson), Money.class));
  }

  @Test
  void theReaderToleratesAttributesAndFieldsItDoesNotKnow() {
    String json =
        """
        {"specversion":"1.0","id":"e-1","source":"/future","type":"zonacards.test.created.v2",
         "newattribute":"x","tenantid":"pe",
         "data":{"name":"n","n":3,"addedLater":{"deep":true}}}
        """;
    EventEnvelope decoded = codec.decode(json);
    assertEquals("e-1", decoded.id());
    assertEquals(new Fixtures.Payload("n", 3), codec.data(decoded, Fixtures.Payload.class));
  }

  @Test
  void aDocumentThatIsNotAnEventIsRejected() {
    assertThrows(EventxException.class, () -> codec.decode("not json"));
    assertThrows(EventxException.class, () -> codec.decode("{\"specversion\":\"1.0\"}"));
    assertThrows(EventxException.class, () -> codec.decode("null"));
    EventEnvelope noData = codec.decode("{\"id\":\"1\",\"type\":\"t\"}");
    assertThrows(EventxException.class, () -> codec.data(noData, Fixtures.Payload.class));
  }

  @Test
  void everyEventGetsItsOwnIdAndACorrelationId() {
    EventEnvelope a = Fixtures.event("t", 1);
    EventEnvelope b = Fixtures.event("t", 1);
    assertTrue(!a.id().equals(b.id()));
    assertNotNull(a.correlationid());
    assertEquals("subject-1", a.subject());
    assertEquals("/test", a.source());
  }
}
