package com.railops.backend;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Applies stored events to the Redis live state (counters, service health, timeline, recent list). */
@Component
public class LiveStateUpdater {

    // Fixed width, so the script can compare times as strings; validation keeps years at 2000-9999.
    static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    /** Read-through cache of the dashboard summary; an applied status change deletes it. */
    static final String SUMMARY_CACHE_KEY = "cache:dashboard:summary";
    private static final RedisScript<Long> APPLY_EVENT =
            RedisScript.of(new ClassPathResource("redis/apply-event.lua"), Long.class);
    private static final RedisScript<Long> APPLY_STATUS_CHANGE =
            RedisScript.of(new ClassPathResource("redis/apply-status-change.lua"), Long.class);

    private final StringRedisTemplate redis;

    public LiveStateUpdater(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Applies the event once, atomically; guarded by {@code processed:{eventId}} for 24h.
     *
     * @return true when applied, false when the event was already applied
     */
    public boolean applyEvent(String eventId, String service, Severity severity, EventStatus status,
                              Instant timestamp) {
        Instant bucketStart = timestamp.truncatedTo(ChronoUnit.MINUTES);
        List<String> keys = List.of(
                "processed:" + eventId,
                "services",
                "service:" + service,
                "events:count",
                "severity:" + severity + ":count",
                "status:" + status + ":count",
                "active:" + severity + ":count",
                timelineKey(bucketStart),
                "recent:events");
        Long applied = redis.execute(APPLY_EVENT, keys, eventId, service, severity.name(), status.name(),
                EVENT_TIME.format(timestamp), Long.toString(bucketStart.getEpochSecond()));
        return applied != null && applied == 1;
    }

    /**
     * Moves one applied event from one status to another in the counters, active counts and service health, and
     * deletes the cached dashboard summary.
     *
     * @return true when applied, false when the service has no live state to update
     */
    public boolean applyStatusChange(String service, Severity severity, EventStatus from, EventStatus to) {
        List<String> keys = List.of(
                "service:" + service,
                "status:" + from + ":count",
                "status:" + to + ":count",
                "active:" + severity + ":count",
                SUMMARY_CACHE_KEY);
        Long applied = redis.execute(APPLY_STATUS_CHANGE, keys, severity.name(), from.name(), to.name());
        return applied != null && applied == 1;
    }

    /** The timeline bucket key for the UTC minute starting at {@code minuteStart}. */
    static String timelineKey(Instant minuteStart) {
        return "timeline:" + BUCKET.format(minuteStart);
    }
}
