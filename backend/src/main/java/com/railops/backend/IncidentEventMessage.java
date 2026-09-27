package com.railops.backend;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Kafka payload from the producer. Sizes match the {@code events} columns so a valid message always fits, and
 * {@code eventId} is limited to characters that are safe as one URL path segment, so {@code /api/events/{eventId}}
 * can always address it ({@code /}, {@code \} and dot-only segments are rejected or rewritten by the server).
 */
public record IncidentEventMessage(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_:-][A-Za-z0-9._:-]*") String eventId,
        @NotNull @Pattern(regexp = "ATS|CBTC|SCADA|TMS|PIS") String source,
        @NotBlank @Size(max = 64) String service,
        @NotNull Severity severity,
        @NotBlank String message,
        @NotNull EventStatus status,
        @NotNull Instant timestamp) {

    static final Instant MIN_TIMESTAMP = Instant.parse("2000-01-01T00:00:00Z");
    static final Instant MAX_TIMESTAMP_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    /** The JDBC driver silently stores instants before Postgres's range as {@code -infinity}. */
    @AssertTrue
    boolean isTimestampInRange() {
        return timestamp == null || (!timestamp.isBefore(MIN_TIMESTAMP) && timestamp.isBefore(MAX_TIMESTAMP_EXCLUSIVE));
    }
}
