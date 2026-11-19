package com.zonacards.eventx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON for envelopes and payloads. Reading is tolerant: unknown attributes and unknown payload
 * fields are ignored, so a producer can add fields without breaking its consumers. Removing or
 * renaming a field needs a new major version of the event type.
 */
public final class EventCodec {

  private final ObjectMapper mapper =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          // Money must cross the wire exactly as written: 50.00 stays 50.00, never 5E+1 or 50.0.
          .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
          .build();

  public String encode(EventEnvelope envelope) {
    try {
      return mapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new EventxException("cannot encode event " + envelope.type(), e);
    }
  }

  public EventEnvelope decode(String json) {
    EventEnvelope envelope;
    try {
      envelope = mapper.readValue(json, EventEnvelope.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new EventxException("malformed event: not a CloudEvents JSON document", e);
    }
    if (envelope == null || isBlank(envelope.id()) || isBlank(envelope.type())) {
      throw new EventxException("malformed event: id and type are required");
    }
    return envelope;
  }

  public JsonNode toData(Object payload) {
    return mapper.valueToTree(payload);
  }

  /** Reads the payload as the consumer's own view of it. */
  public <T> T data(EventEnvelope envelope, Class<T> type) {
    try {
      T value = mapper.treeToValue(envelope.data(), type);
      if (value == null) {
        throw new EventxException("event " + envelope.type() + " has no data");
      }
      return value;
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new EventxException("event " + envelope.type() + " does not match " + type.getSimpleName(), e);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
