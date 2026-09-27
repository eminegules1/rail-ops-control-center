package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LiveStateUpdaterIntegrationTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4.11-alpine").withExposedPorts(6379);

    private static final String SERVICE = "signal-service";
    private static final DateTimeFormatter BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static LiveStateUpdater updater;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        updater = new LiveStateUpdater(redis);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void appliesOpenCriticalEvent() {
        Instant now = Instant.now();

        assertThat(updater.applyEvent("EVT-1", SERVICE, Severity.CRITICAL, EventStatus.OPEN, now)).isTrue();

        assertThat(counter("events:count")).isEqualTo("1");
        assertThat(counter("severity:CRITICAL:count")).isEqualTo("1");
        assertThat(counter("status:OPEN:count")).isEqualTo("1");
        assertThat(counter("active:CRITICAL:count")).isEqualTo("1");
        assertThat(redis.opsForSet().members("services")).containsExactly(SERVICE);
        assertThat(service()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "active:INFO", "0",
                "active:WARNING", "0",
                "active:MAJOR", "0",
                "active:CRITICAL", "1",
                "openCount", "1",
                "activeCount", "1",
                "status", "DOWN",
                "lastEventTime", LiveStateUpdater.EVENT_TIME.format(now),
                "latestSeverity", "CRITICAL"));
        assertThat(redis.opsForList().range("recent:events", 0, -1)).containsExactly("EVT-1");
        assertThat(redis.getExpire("processed:EVT-1")).isBetween(1L, 86400L);
    }

    @Test
    void appliesEachEventOnce() {
        Instant now = Instant.now();
        updater.applyEvent("EVT-1", SERVICE, Severity.CRITICAL, EventStatus.OPEN, now);

        assertThat(updater.applyEvent("EVT-1", SERVICE, Severity.CRITICAL, EventStatus.OPEN, now)).isFalse();

        assertThat(counter("events:count")).isEqualTo("1");
        assertThat(service()).containsEntry("activeCount", "1").containsEntry("openCount", "1");
        assertThat(redis.opsForList().size("recent:events")).isEqualTo(1);
        assertThat(timeline(now)).containsEntry("CRITICAL", "1");
    }

    @Test
    void acknowledgedWarningDegradesServiceButIsNotOpen() {
        updater.applyEvent("EVT-1", SERVICE, Severity.WARNING, EventStatus.ACKNOWLEDGED, Instant.now());

        assertThat(service())
                .containsEntry("status", "DEGRADED")
                .containsEntry("active:WARNING", "1")
                .containsEntry("openCount", "0")
                .containsEntry("activeCount", "1");
        assertThat(counter("status:ACKNOWLEDGED:count")).isEqualTo("1");
    }

    @Test
    void resolvedEventIsCountedButNotActive() {
        updater.applyEvent("EVT-1", SERVICE, Severity.CRITICAL, EventStatus.RESOLVED, Instant.now());

        assertThat(counter("status:RESOLVED:count")).isEqualTo("1");
        assertThat(counter("severity:CRITICAL:count")).isEqualTo("1");
        assertThat(counter("active:CRITICAL:count")).isNull();
        assertThat(service())
                .containsEntry("status", "HEALTHY")
                .containsEntry("active:CRITICAL", "0")
                .containsEntry("activeCount", "0");
    }

    @Test
    void activeMajorAloneDegradesService() {
        updater.applyEvent("EVT-1", SERVICE, Severity.MAJOR, EventStatus.OPEN, Instant.now());

        assertThat(service()).containsEntry("status", "DEGRADED").containsEntry("active:MAJOR", "1");
    }

    @Test
    void activeInfoOnlyKeepsServiceHealthy() {
        updater.applyEvent("EVT-1", SERVICE, Severity.INFO, EventStatus.OPEN, Instant.now());

        assertThat(service()).containsEntry("status", "HEALTHY").containsEntry("active:INFO", "1");
    }

    @Test
    void latestFieldsFollowEventTimeNotArrivalOrder() {
        Instant newer = Instant.now();
        updater.applyEvent("EVT-1", SERVICE, Severity.MAJOR, EventStatus.OPEN, newer.minusSeconds(60));
        updater.applyEvent("EVT-2", SERVICE, Severity.INFO, EventStatus.OPEN, newer);
        updater.applyEvent("EVT-3", SERVICE, Severity.CRITICAL, EventStatus.OPEN, newer.minusSeconds(30));

        assertThat(service())
                .containsEntry("lastEventTime", LiveStateUpdater.EVENT_TIME.format(newer))
                .containsEntry("latestSeverity", "INFO")
                .containsEntry("status", "DOWN");
    }

    @Test
    void timelineBucketsRecentEventsWithBoundedTtl() {
        Instant now = Instant.now();
        updater.applyEvent("EVT-1", SERVICE, Severity.MAJOR, EventStatus.OPEN, now);
        updater.applyEvent("EVT-2", SERVICE, Severity.MAJOR, EventStatus.RESOLVED, now);

        assertThat(timeline(now)).containsExactlyEntriesOf(Map.of("MAJOR", "2"));
        assertThat(redis.getExpire(timelineKey(now))).isBetween(1L, 7260L);
    }

    @Test
    void timelineSkipsEventsOlderThanTheWindow() {
        Instant old = Instant.now().minus(3, ChronoUnit.HOURS);

        updater.applyEvent("EVT-1", SERVICE, Severity.MAJOR, EventStatus.OPEN, old);

        assertThat(redis.hasKey(timelineKey(old))).isFalse();
        assertThat(counter("events:count")).isEqualTo("1");
    }

    @Test
    void timelineTtlIsCappedForFutureEvents() {
        Instant future = Instant.now().plus(365, ChronoUnit.DAYS);

        updater.applyEvent("EVT-1", SERVICE, Severity.INFO, EventStatus.OPEN, future);

        assertThat(redis.getExpire(timelineKey(future))).isBetween(1L, 7260L);
    }

    @Test
    void recentListKeepsNewestFifty() {
        Instant now = Instant.now();
        for (int i = 1; i <= 55; i++) {
            updater.applyEvent("EVT-" + i, SERVICE, Severity.INFO, EventStatus.OPEN, now);
        }

        List<String> recent = redis.opsForList().range("recent:events", 0, -1);
        assertThat(recent).hasSize(50).startsWith("EVT-55", "EVT-54").endsWith("EVT-6");
    }

    @Test
    void acknowledgingKeepsEventActiveButNotOpen() {
        updater.applyEvent("EVT-1", SERVICE, Severity.CRITICAL, EventStatus.OPEN, Instant.now());

        assertThat(updater.applyStatusChange(SERVICE, Severity.CRITICAL, EventStatus.OPEN, EventStatus.ACKNOWLEDGED))
                .isTrue();

        assertThat(counter("status:OPEN:count")).isEqualTo("0");
        assertThat(counter("status:ACKNOWLEDGED:count")).isEqualTo("1");
        assertThat(counter("active:CRITICAL:count")).isEqualTo("1");
        assertThat(service())
                .containsEntry("active:CRITICAL", "1")
                .containsEntry("openCount", "0")
                .containsEntry("activeCount", "1")
                .containsEntry("status", "DOWN");
    }

    @Test
    void resolvingLastActiveCriticalMakesServiceHealthyAndReopeningMakesItDownAgain() {
        updater.applyEvent("EVT-1", SERVICE, Severity.CRITICAL, EventStatus.OPEN, Instant.now());
        updater.applyEvent("EVT-2", SERVICE, Severity.INFO, EventStatus.OPEN, Instant.now());

        updater.applyStatusChange(SERVICE, Severity.CRITICAL, EventStatus.OPEN, EventStatus.RESOLVED);

        assertThat(counter("status:OPEN:count")).isEqualTo("1");
        assertThat(counter("status:RESOLVED:count")).isEqualTo("1");
        assertThat(counter("active:CRITICAL:count")).isEqualTo("0");
        assertThat(service())
                .containsEntry("active:CRITICAL", "0")
                .containsEntry("openCount", "1")
                .containsEntry("activeCount", "1")
                .containsEntry("status", "HEALTHY");

        updater.applyStatusChange(SERVICE, Severity.CRITICAL, EventStatus.RESOLVED, EventStatus.OPEN);

        assertThat(counter("status:OPEN:count")).isEqualTo("2");
        assertThat(counter("status:RESOLVED:count")).isEqualTo("0");
        assertThat(counter("active:CRITICAL:count")).isEqualTo("1");
        assertThat(service())
                .containsEntry("active:CRITICAL", "1")
                .containsEntry("openCount", "2")
                .containsEntry("activeCount", "2")
                .containsEntry("status", "DOWN");
    }

    @Test
    void resolvingAcknowledgedWarningClearsDegradedHealth() {
        updater.applyEvent("EVT-1", SERVICE, Severity.WARNING, EventStatus.ACKNOWLEDGED, Instant.now());

        updater.applyStatusChange(SERVICE, Severity.WARNING, EventStatus.ACKNOWLEDGED, EventStatus.RESOLVED);

        assertThat(counter("status:ACKNOWLEDGED:count")).isEqualTo("0");
        assertThat(counter("status:RESOLVED:count")).isEqualTo("1");
        assertThat(counter("active:WARNING:count")).isEqualTo("0");
        assertThat(service())
                .containsEntry("active:WARNING", "0")
                .containsEntry("openCount", "0")
                .containsEntry("activeCount", "0")
                .containsEntry("status", "HEALTHY");
    }

    @Test
    void statusChangeLeavesEventTotalsTimelineAndRecentListAlone() {
        Instant now = Instant.now();
        updater.applyEvent("EVT-1", SERVICE, Severity.MAJOR, EventStatus.OPEN, now);

        updater.applyStatusChange(SERVICE, Severity.MAJOR, EventStatus.OPEN, EventStatus.RESOLVED);

        assertThat(counter("events:count")).isEqualTo("1");
        assertThat(counter("severity:MAJOR:count")).isEqualTo("1");
        assertThat(timeline(now)).containsExactlyEntriesOf(Map.of("MAJOR", "1"));
        assertThat(redis.opsForList().range("recent:events", 0, -1)).containsExactly("EVT-1");
        assertThat(service())
                .containsEntry("lastEventTime", LiveStateUpdater.EVENT_TIME.format(now))
                .containsEntry("latestSeverity", "MAJOR");
    }

    @Test
    void statusChangeDeletesTheCachedSummary() {
        updater.applyEvent("EVT-1", SERVICE, Severity.CRITICAL, EventStatus.OPEN, Instant.now());
        redis.opsForValue().set(LiveStateUpdater.SUMMARY_CACHE_KEY, "{}");

        updater.applyStatusChange(SERVICE, Severity.CRITICAL, EventStatus.OPEN, EventStatus.ACKNOWLEDGED);

        assertThat(redis.hasKey(LiveStateUpdater.SUMMARY_CACHE_KEY)).isFalse();
    }

    @Test
    void statusChangeWithoutLiveStateKeepsTheCachedSummary() {
        redis.opsForValue().set(LiveStateUpdater.SUMMARY_CACHE_KEY, "{}");

        updater.applyStatusChange(SERVICE, Severity.CRITICAL, EventStatus.OPEN, EventStatus.RESOLVED);

        assertThat(redis.opsForValue().get(LiveStateUpdater.SUMMARY_CACHE_KEY)).isEqualTo("{}");
    }

    @Test
    void statusChangeForServiceWithoutLiveStateChangesNothing() {
        assertThat(updater.applyStatusChange(SERVICE, Severity.CRITICAL, EventStatus.OPEN, EventStatus.RESOLVED))
                .isFalse();

        assertThat(redis.keys("*")).isEmpty();
    }

    private static String counter(String key) {
        return redis.opsForValue().get(key);
    }

    private static Map<Object, Object> service() {
        return redis.opsForHash().entries("service:" + SERVICE);
    }

    private static Map<Object, Object> timeline(Instant timestamp) {
        return redis.opsForHash().entries(timelineKey(timestamp));
    }

    private static String timelineKey(Instant timestamp) {
        return "timeline:" + BUCKET.format(timestamp);
    }
}
