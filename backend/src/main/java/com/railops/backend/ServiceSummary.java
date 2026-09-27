package com.railops.backend;

import java.time.Instant;

/** A service's health in the dashboard summary. */
public record ServiceSummary(String name, ServiceHealth status, Instant lastEventTime) {
}
