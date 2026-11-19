package com.zonacards.eventx;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the outbox.
 *
 * @param id the envelope id; also used as the batch entry id towards the broker
 * @param attempts how many times it has been claimed, including the current claim
 */
public record OutboxMessage(
    UUID id,
    DestinationKind kind,
    String destination,
    String type,
    String tenantId,
    String payload,
    int attempts,
    Instant createdAt) {}
