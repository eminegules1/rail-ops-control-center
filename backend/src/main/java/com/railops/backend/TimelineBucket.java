package com.railops.backend;

import java.time.Instant;
import java.util.Map;

/** Events per severity whose {@code timestamp} falls in the UTC minute starting at {@code minute}. */
public record TimelineBucket(Instant minute, Map<Severity, Long> counts) {
}
