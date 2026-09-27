package com.railops.backend;

/** Optional list filters; null fields do not filter. */
public record EventFilter(Severity severity, EventStatus status, String source, String service, String q) {
}
