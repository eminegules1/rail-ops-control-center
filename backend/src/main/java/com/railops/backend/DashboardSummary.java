package com.railops.backend;

import java.util.List;
import java.util.Map;

/**
 * Dashboard KPIs from the Redis live state; cached for a few seconds.
 *
 * @param criticalEvents CRITICAL events that are not RESOLVED
 * @param severityDistribution all events by severity
 */
public record DashboardSummary(
        long totalEvents,
        long openEvents,
        long acknowledgedEvents,
        long criticalEvents,
        Map<Severity, Long> severityDistribution,
        List<ServiceSummary> services) {
}
