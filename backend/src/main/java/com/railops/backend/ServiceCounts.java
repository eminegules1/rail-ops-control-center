package com.railops.backend;

/** One service's open/active counts from Postgres, grouped the way the Redis service hash tracks them. */
record ServiceCounts(
        String service,
        long openCount,
        long activeCount,
        long activeCritical,
        long activeMajor,
        long activeWarning) {
}
