package com.railops.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Read side of the dashboard: Redis live state, plus Postgres rows for the recent events. */
@Service
public class DashboardQueryService {

    static final Duration SUMMARY_TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final IncidentEventRepository repository;
    private final ObjectMapper json;

    public DashboardQueryService(StringRedisTemplate redis, IncidentEventRepository repository, ObjectMapper json) {
        this.redis = redis;
        this.repository = repository;
        this.json = json;
    }

    /** The cached summary when present; otherwise builds it from the counters and caches it for 5s. */
    public DashboardSummary summary() {
        String cached = redis.opsForValue().get(LiveStateUpdater.SUMMARY_CACHE_KEY);
        if (cached != null) {
            try {
                return json.readValue(cached, DashboardSummary.class);
            } catch (JsonProcessingException e) {
                // An unreadable entry is treated as a miss and overwritten below.
            }
        }
        DashboardSummary summary = buildSummary();
        try {
            redis.opsForValue().set(LiveStateUpdater.SUMMARY_CACHE_KEY, json.writeValueAsString(summary),
                    SUMMARY_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dashboard summary is not serializable", e);
        }
        return summary;
    }

    /** Every known service's live state, sorted by name. */
    public List<ServiceState> services() {
        Set<String> names = redis.opsForSet().members("services");
        if (names == null) {
            return List.of();
        }
        List<ServiceState> services = new ArrayList<>();
        for (String name : names.stream().sorted().toList()) {
            Map<Object, Object> hash = redis.opsForHash().entries("service:" + name);
            if (!hash.isEmpty()) {
                services.add(new ServiceState(name,
                        parse(hash.get("status"), ServiceHealth::valueOf),
                        parse(hash.get("lastEventTime"), Instant::parse),
                        parse(hash.get("latestSeverity"), Severity::valueOf),
                        toLong(hash.get("openCount")),
                        toLong(hash.get("activeCount"))));
            }
        }
        return services;
    }

    public List<TimelineBucket> timeline(int minutes) {
        return timeline(minutes, Instant.now());
    }

    /** {@code minutes} per-minute buckets, oldest first, ending with the minute that contains {@code now}. */
    List<TimelineBucket> timeline(int minutes, Instant now) {
        Instant last = now.truncatedTo(ChronoUnit.MINUTES);
        List<Instant> starts = new ArrayList<>(minutes);
        for (int i = minutes - 1; i >= 0; i--) {
            starts.add(last.minus(i, ChronoUnit.MINUTES));
        }
        // One round trip; raw bytes so the template deserializes each reply with its string serializers.
        List<Object> hashes = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Instant start : starts) {
                connection.hashCommands().hGetAll(
                        LiveStateUpdater.timelineKey(start).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        List<TimelineBucket> buckets = new ArrayList<>(minutes);
        for (int i = 0; i < minutes; i++) {
            Map<?, ?> hash = (Map<?, ?>) hashes.get(i);
            Map<Severity, Long> counts = new EnumMap<>(Severity.class);
            for (Severity severity : Severity.values()) {
                counts.put(severity, hash == null ? 0 : toLong(hash.get(severity.name())));
            }
            buckets.add(new TimelineBucket(starts.get(i), counts));
        }
        return buckets;
    }

    /**
     * Up to {@code limit} of the most recently applied events, newest first. An id pushed twice (a redelivery
     * after the apply-once guard expired) is shown once; an id without a Postgres row is skipped.
     */
    public List<EventResponse> recentEvents(int limit) {
        List<String> ids = redis.opsForList().range("recent:events", 0, limit - 1L);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>(ids);
        Map<String, IncidentEvent> rows = repository.findByEventIdIn(unique).stream()
                .collect(Collectors.toMap(IncidentEvent::getEventId, Function.identity()));
        return unique.stream()
                .map(rows::get)
                .filter(Objects::nonNull)
                .map(EventResponse::from)
                .toList();
    }

    private DashboardSummary buildSummary() {
        List<String> keys = new ArrayList<>(List.of("events:count", "status:OPEN:count",
                "status:ACKNOWLEDGED:count", "active:CRITICAL:count"));
        for (Severity severity : Severity.values()) {
            keys.add("severity:" + severity + ":count");
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        Map<Severity, Long> distribution = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            distribution.put(severity, toLong(values.get(4 + severity.ordinal())));
        }
        List<ServiceSummary> services = services().stream()
                .map(service -> new ServiceSummary(service.name(), service.status(), service.lastEventTime()))
                .toList();
        return new DashboardSummary(toLong(values.get(0)), toLong(values.get(1)), toLong(values.get(2)),
                toLong(values.get(3)), distribution, services);
    }

    private static <T> T parse(Object value, Function<String, T> parser) {
        return value == null ? null : parser.apply(value.toString());
    }

    private static long toLong(Object value) {
        return value == null ? 0 : Long.parseLong(value.toString());
    }
}
