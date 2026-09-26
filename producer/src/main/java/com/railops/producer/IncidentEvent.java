package com.railops.producer;

import java.time.Instant;

/** Kafka payload; field names are shared with the backend entity and API DTOs. */
public record IncidentEvent(
        String eventId,
        String source,
        String service,
        Severity severity,
        String message,
        EventStatus status,
        Instant timestamp) {
}
