package com.railops.backend;

import java.time.Instant;

/** One service's live state, as {@code GET /api/services} returns it. */
public record ServiceState(
        String name,
        ServiceHealth status,
        Instant lastEventTime,
        Severity latestSeverity,
        long openCount,
        long activeCount) {
}
