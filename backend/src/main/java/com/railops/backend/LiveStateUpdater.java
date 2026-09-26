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
    private static final RedisScript<Long> APPLY_EVENT =
            RedisScript.of(new ClassPathResource("redis/apply-event.lua"), Long.class);

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
                "timeline:" + BUCKET.format(bucketStart),
                "recent:events");
        Long applied = redis.execute(APPLY_EVENT, keys, eventId, service, severity.name(), status.name(),
                EVENT_TIME.format(timestamp), Long.toString(bucketStart.getEpochSecond()));
        return applied != null && applied == 1;
    }
}
