package com.railops.backend;

import java.time.Instant;

/** API view of a stored event; list items and detail share it. */
public record EventResponse(
        String eventId,
        String source,
        String service,
        Severity severity,
        String message,
        EventStatus status,
        Instant timestamp,
        Instant receivedAt,
        Instant updatedAt) {

    static EventResponse from(IncidentEvent event) {
        return new EventResponse(event.getEventId(), event.getSource(), event.getService(), event.getSeverity(),
                event.getMessage(), event.getStatus(), event.getTimestamp(), event.getReceivedAt(),
                event.getUpdatedAt());
    }
}
