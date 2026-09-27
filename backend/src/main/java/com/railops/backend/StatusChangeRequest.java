package com.railops.backend;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/events/{eventId}/status}; clients send no version. */
public record StatusChangeRequest(@NotNull(message = "is required") EventStatus status) {
}
