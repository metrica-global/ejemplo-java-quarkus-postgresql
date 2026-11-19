package com.zonacards.eventx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * The envelope of every message: CloudEvents 1.0 in structured JSON mode, plus three extension
 * attributes. Attribute names are lower-case because the specification says so.
 *
 * @param id unique per event; consumers deduplicate on it
 * @param source the service that produced the event, as a URI reference
 * @param type {@code zonacards.<domain>.<fact>.v<major>}; also sent as a message attribute so the
 *     broker can filter without parsing the body
 * @param subject the id of the aggregate the event is about
 * @param tenantid extension: the tenant that owns the aggregate
 * @param correlationid extension: ties together everything caused by one request
 * @param traceparent extension: W3C trace context of the producer
 * @param data the payload; its schema is owned by the producer and versioned with {@code type}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
    String specversion,
    String id,
    String source,
    String type,
    String subject,
    Instant time,
    String datacontenttype,
    String tenantid,
    String correlationid,
    String traceparent,
    JsonNode data) {

  public static final String SPEC_VERSION = "1.0";
  public static final String CONTENT_TYPE = "application/json";
}
