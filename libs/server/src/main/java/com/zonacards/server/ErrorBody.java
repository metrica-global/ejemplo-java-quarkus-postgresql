package com.zonacards.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * The one error shape of the API. Clients branch on {@code code}, never on the message.
 *
 * @param error human-readable message
 * @param code stable identifier, {@code <DOMAIN>_<REASON>}
 * @param requestId the id to quote when asking for support; it is in every log line of the request
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorBody(
    String error,
    String code,
    String type,
    int status,
    Map<String, Object> details,
    @JsonProperty("request_id") String requestId) {}
