package com.railops.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/** Read side of the dashboard: the Redis live state, falling back to PostgreSQL when Redis is unavailable. */
@Service
public class DashboardQueryService {

    static final Duration SUMMARY_TTL = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(DashboardQueryService.class);
    private static final RedisScript<Long> CACHE_SUMMARY =
            RedisScript.of(new ClassPathResource("redis/cache-summary.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final IncidentEventRepository repository;
    private final ObjectMapper json;
    private final CircuitBreaker circuitBreaker;

    public DashboardQueryService(StringRedisTemplate redis, IncidentEventRepository repository, ObjectMapper json,
                                 CircuitBreaker circuitBreaker) {
        this.redis = redis;
        this.repository = repository;
        this.json = json;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * The cached summary when present; otherwise builds it from the counters and caches it for 5s, unless a status
     * change was applied while it was being built. Served from Postgres, uncached, when Redis is unavailable.
     */
    public DashboardSummary summary() {
        List<String> entry;
        try {
            entry = circuitBreaker.executeSupplier(() -> redis.opsForValue()
                    .multiGet(List.of(LiveStateUpdater.SUMMARY_CACHE_KEY, LiveStateUpdater.SUMMARY_VERSION_KEY)));
        } catch (DataAccessException | CallNotPermittedException e) {
            logFallback("Dashboard summary served from Postgres; Redis unavailable", e);
            return summaryFromPostgres();
        }
        String cached = entry.get(0);
        if (cached != null) {
            try {
                return json.readValue(cached, DashboardSummary.class);
            } catch (JsonProcessingException e) {
                // An unreadable entry is treated as a miss and overwritten below.
            }
        }
        DashboardSummary summary = buildSummary();
        try {
            cacheSummary(summary, entry.get(1));
        } catch (DataAccessException | CallNotPermittedException e) {
            logFallback("Dashboard summary not cached; Redis unavailable", e);
        }
        return summary;
    }

    /**
     * Caches {@code summary} for 5s if the summary version still equals {@code version}, read before the summary
     * was built. A status change bumps the version, so a summary built from the counters it changed is not cached.
     *
     * @return true when cached
     */
    boolean cacheSummary(DashboardSummary summary, String version) {
        String value;
        try {
            value = json.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dashboard summary is not serializable", e);
        }
        Long cached = circuitBreaker.executeSupplier(() -> redis.execute(CACHE_SUMMARY,
                List.of(LiveStateUpdater.SUMMARY_CACHE_KEY, LiveStateUpdater.SUMMARY_VERSION_KEY),
                value, version == null ? "" : version, Long.toString(SUMMARY_TTL.toMillis())));
        return cached != null && cached == 1;
    }

    /** Every known service's live state, sorted by name; from Postgres when Redis is unavailable. */
    public List<ServiceState> services() {
        try {
            return circuitBreaker.executeSupplier(this::servicesFromRedis);
        } catch (DataAccessException | CallNotPermittedException e) {
            logFallback("Service states served from Postgres; Redis unavailable", e);
            return servicesFromPostgres();
        }
    }

    private List<ServiceState> servicesFromRedis() {
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

    private List<ServiceState> servicesFromPostgres() {
        Map<String, ServiceCounts> counts = repository.aggregateServiceCounts().stream()
                .collect(Collectors.toMap(ServiceCounts::service, Function.identity()));
        List<ServiceState> services = new ArrayList<>();
        for (IncidentEvent latest : repository.findLatestPerService()) {
            ServiceCounts serviceCounts = counts.get(latest.getService());
            if (serviceCounts == null) {
                continue;
            }
            services.add(new ServiceState(latest.getService(),
                    healthOf(serviceCounts.activeCritical(), serviceCounts.activeMajor(), serviceCounts.activeWarning()),
                    latest.getTimestamp(), latest.getSeverity(), serviceCounts.openCount(), serviceCounts.activeCount()));
        }
        return services.stream().sorted((a, b) -> a.name().compareTo(b.name())).toList();
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
        try {
            return circuitBreaker.executeSupplier(() -> timelineFromRedis(starts));
        } catch (DataAccessException | CallNotPermittedException e) {
            logFallback("Timeline served from Postgres; Redis unavailable", e);
            return timelineFromPostgres(starts);
        }
    }

    private List<TimelineBucket> timelineFromRedis(List<Instant> starts) {
        // One round trip; raw bytes so the template deserializes each reply with its string serializers.
        List<Object> hashes = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Instant start : starts) {
                connection.hashCommands().hGetAll(
                        LiveStateUpdater.timelineKey(start).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        List<TimelineBucket> buckets = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            Map<?, ?> hash = (Map<?, ?>) hashes.get(i);
            Map<Severity, Long> counts = new EnumMap<>(Severity.class);
            for (Severity severity : Severity.values()) {
                counts.put(severity, hash == null ? 0 : toLong(hash.get(severity.name())));
            }
            buckets.add(new TimelineBucket(starts.get(i), counts));
        }
        return buckets;
    }

    private List<TimelineBucket> timelineFromPostgres(List<Instant> starts) {
        Map<Instant, Map<Severity, Long>> byMinute = groupTimelineRows(repository.timelineCounts(starts.get(0)));
        List<TimelineBucket> buckets = new ArrayList<>(starts.size());
        for (Instant start : starts) {
            Map<Severity, Long> found = byMinute.get(start);
            Map<Severity, Long> counts = new EnumMap<>(Severity.class);
            for (Severity severity : Severity.values()) {
                counts.put(severity, found == null ? 0 : found.getOrDefault(severity, 0L));
            }
            buckets.add(new TimelineBucket(start, counts));
        }
        return buckets;
    }

    /**
     * Up to {@code limit} of the most recently applied events, newest first. An id pushed twice (a redelivery
     * after the apply-once guard expired) is shown once; an id without a Postgres row is skipped. Falls back to the
     * most recently received Postgres rows when Redis is unavailable.
     */
    public List<EventResponse> recentEvents(int limit) {
        List<String> ids;
        try {
            ids = circuitBreaker.executeSupplier(() -> redis.opsForList().range("recent:events", 0, limit - 1L));
        } catch (DataAccessException | CallNotPermittedException e) {
            logFallback("Recent events served from Postgres; Redis unavailable", e);
            return repository.findByOrderByReceivedAtDesc(PageRequest.of(0, limit)).stream()
                    .map(EventResponse::from)
                    .toList();
        }
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

    /** Built from the live counters; falls back to Postgres aggregates when Redis is unavailable. */
    DashboardSummary buildSummary() {
        try {
            return circuitBreaker.executeSupplier(this::buildSummaryFromRedis);
        } catch (DataAccessException | CallNotPermittedException e) {
            logFallback("Dashboard summary built from Postgres; Redis unavailable", e);
            return summaryFromPostgres();
        }
    }

    private DashboardSummary buildSummaryFromRedis() {
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
        List<ServiceSummary> services = servicesFromRedis().stream()
                .map(service -> new ServiceSummary(service.name(), service.status(), service.lastEventTime()))
                .toList();
        return new DashboardSummary(toLong(values.get(0)), toLong(values.get(1)), toLong(values.get(2)),
                toLong(values.get(3)), distribution, services);
    }

    private DashboardSummary summaryFromPostgres() {
        long total = repository.count();
        long open = repository.countByStatus(EventStatus.OPEN);
        long acknowledged = repository.countByStatus(EventStatus.ACKNOWLEDGED);
        long criticalActive = repository.countBySeverityAndStatusNot(Severity.CRITICAL, EventStatus.RESOLVED);
        Map<Severity, Long> distribution = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            distribution.put(severity, repository.countBySeverity(severity));
        }
        List<ServiceSummary> services = servicesFromPostgres().stream()
                .map(service -> new ServiceSummary(service.name(), service.status(), service.lastEventTime()))
                .toList();
        return new DashboardSummary(total, open, acknowledged, criticalActive, distribution, services);
    }

    /** One WARN line per fallback, without the stack trace a Redis outage would repeat on every request. */
    private static void logFallback(String message, RuntimeException cause) {
        log.warn("{}: {}: {}", message, cause.getClass().getSimpleName(), cause.getMessage());
        log.debug("Redis failure behind the fallback", cause);
    }

    /** Same precedence the {@code apply-event} Lua script uses to derive a service's health. */
    private static ServiceHealth healthOf(long activeCritical, long activeMajor, long activeWarning) {
        if (activeCritical > 0) {
            return ServiceHealth.DOWN;
        }
        if (activeMajor > 0 || activeWarning > 0) {
            return ServiceHealth.DEGRADED;
        }
        return ServiceHealth.HEALTHY;
    }

    /**
     * Groups {@link IncidentEventRepository#timelineCounts} rows ({@code bucket, severity, count}) by minute, for
     * both the dashboard read fallback and the reconciler's rebuild.
     */
    static Map<Instant, Map<Severity, Long>> groupTimelineRows(List<Object[]> rows) {
        Map<Instant, Map<Severity, Long>> byMinute = new HashMap<>();
        for (Object[] row : rows) {
            Instant minute = toInstant(row[0]);
            Severity severity = Severity.valueOf((String) row[1]);
            byMinute.computeIfAbsent(minute, key -> new EnumMap<>(Severity.class)).put(severity, toLong(row[2]));
        }
        return byMinute;
    }

    /** The driver may return a {@code timestamptz} native-query column as either type, depending on version. */
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unexpected timeline bucket type: " + value.getClass());
    }

    private static <T> T parse(Object value, Function<String, T> parser) {
        return value == null ? null : parser.apply(value.toString());
    }

    private static long toLong(Object value) {
        return value == null ? 0 : Long.parseLong(value.toString());
    }
}
