package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DashboardQueryServiceIntegrationTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4.11-alpine").withExposedPorts(6379);

    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static LiveStateUpdater updater;

    private final IncidentEventRepository repository = mock(IncidentEventRepository.class);
    private final DashboardQueryService dashboard = new DashboardQueryService(redis, repository, JSON);

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
    void summaryCountsAppliedEvents() {
        Instant now = Instant.parse("2026-09-27T12:30:05.123Z");
        updater.applyEvent("EVT-1", "signal-service", Severity.CRITICAL, EventStatus.OPEN, now);
        updater.applyEvent("EVT-2", "signal-service", Severity.CRITICAL, EventStatus.RESOLVED, now);
        updater.applyEvent("EVT-3", "pis-service", Severity.WARNING, EventStatus.ACKNOWLEDGED, now.minusSeconds(5));
        updater.applyEvent("EVT-4", "pis-service", Severity.INFO, EventStatus.OPEN, now.minusSeconds(10));

        DashboardSummary summary = dashboard.summary();

        assertThat(summary.totalEvents()).isEqualTo(4);
        assertThat(summary.openEvents()).isEqualTo(2);
        assertThat(summary.acknowledgedEvents()).isEqualTo(1);
        assertThat(summary.criticalEvents()).isEqualTo(1);
        assertThat(summary.severityDistribution()).containsExactlyEntriesOf(
                severities(1, 1, 0, 2));
        assertThat(summary.services()).containsExactly(
                new ServiceSummary("pis-service", ServiceHealth.DEGRADED, now.minusSeconds(5)),
                new ServiceSummary("signal-service", ServiceHealth.DOWN, now));
    }

    @Test
    void summaryOfEmptyRedisIsAllZero() {
        DashboardSummary summary = dashboard.summary();

        assertThat(summary).isEqualTo(new DashboardSummary(0, 0, 0, 0, severities(0, 0, 0, 0), List.of()));
    }

    @Test
    void summaryIsCachedForFiveSeconds() {
        updater.applyEvent("EVT-1", "signal-service", Severity.INFO, EventStatus.OPEN, Instant.now());
        DashboardSummary first = dashboard.summary();

        updater.applyEvent("EVT-2", "signal-service", Severity.INFO, EventStatus.OPEN, Instant.now());

        assertThat(redis.getExpire(LiveStateUpdater.SUMMARY_CACHE_KEY)).isBetween(1L, 5L);
        assertThat(dashboard.summary()).isEqualTo(first);
        assertThat(dashboard.summary().totalEvents()).isEqualTo(1);

        redis.delete(LiveStateUpdater.SUMMARY_CACHE_KEY);

        assertThat(dashboard.summary().totalEvents()).isEqualTo(2);
    }

    @Test
    void appliedStatusChangeEvictsTheCachedSummary() {
        updater.applyEvent("EVT-1", "signal-service", Severity.CRITICAL, EventStatus.OPEN, Instant.now());
        assertThat(dashboard.summary().criticalEvents()).isEqualTo(1);

        updater.applyStatusChange("signal-service", Severity.CRITICAL, EventStatus.OPEN, EventStatus.RESOLVED);

        DashboardSummary summary = dashboard.summary();
        assertThat(summary.criticalEvents()).isZero();
        assertThat(summary.openEvents()).isZero();
        assertThat(summary.services()).extracting(ServiceSummary::status).containsExactly(ServiceHealth.HEALTHY);
    }

    @Test
    void unreadableCacheEntryIsRebuilt() {
        updater.applyEvent("EVT-1", "signal-service", Severity.INFO, EventStatus.OPEN, Instant.now());
        redis.opsForValue().set(LiveStateUpdater.SUMMARY_CACHE_KEY, "not json");

        assertThat(dashboard.summary().totalEvents()).isEqualTo(1);
        assertThat(redis.opsForValue().get(LiveStateUpdater.SUMMARY_CACHE_KEY)).startsWith("{");
    }

    @Test
    void servicesAreSortedByNameWithTheirLiveState() {
        Instant now = Instant.parse("2026-09-27T12:30:05.123Z");
        updater.applyEvent("EVT-1", "signal-service", Severity.MAJOR, EventStatus.OPEN, now);
        updater.applyEvent("EVT-2", "signal-service", Severity.INFO, EventStatus.ACKNOWLEDGED, now.plusSeconds(1));
        updater.applyEvent("EVT-3", "ats-service", Severity.INFO, EventStatus.RESOLVED, now);

        assertThat(dashboard.services()).containsExactly(
                new ServiceState("ats-service", ServiceHealth.HEALTHY, now, Severity.INFO, 0, 0),
                new ServiceState("signal-service", ServiceHealth.DEGRADED, now.plusSeconds(1), Severity.INFO, 1, 2));
    }

    @Test
    void servicesSkipAMemberWithoutState() {
        updater.applyEvent("EVT-1", "signal-service", Severity.INFO, EventStatus.OPEN, Instant.now());
        redis.opsForSet().add("services", "ghost-service");

        assertThat(dashboard.services()).extracting(ServiceState::name).containsExactly("signal-service");
    }

    @Test
    void servicesOfEmptyRedisIsEmpty() {
        assertThat(dashboard.services()).isEmpty();
    }

    @Test
    void timelineIsZeroFilledOldestFirstEndingAtTheCurrentMinute() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
        Instant current = now.truncatedTo(ChronoUnit.MINUTES);
        updater.applyEvent("EVT-1", "signal-service", Severity.CRITICAL, EventStatus.OPEN, current.plusSeconds(1));
        updater.applyEvent("EVT-2", "signal-service", Severity.MAJOR, EventStatus.OPEN, current.plusSeconds(2));
        updater.applyEvent("EVT-3", "signal-service", Severity.MAJOR, EventStatus.OPEN, current.minusSeconds(120));

        List<TimelineBucket> buckets = dashboard.timeline(3, now);

        assertThat(buckets).containsExactly(
                new TimelineBucket(current.minusSeconds(120), severities(0, 0, 1, 0)),
                new TimelineBucket(current.minusSeconds(60), severities(0, 0, 0, 0)),
                new TimelineBucket(current, severities(0, 0, 1, 1)));
    }

    @Test
    void timelineExcludesBucketsOutsideTheWindow() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
        Instant current = now.truncatedTo(ChronoUnit.MINUTES);
        updater.applyEvent("EVT-1", "signal-service", Severity.INFO, EventStatus.OPEN, current.minusSeconds(120));
        updater.applyEvent("EVT-2", "signal-service", Severity.INFO, EventStatus.OPEN, current.plusSeconds(60));

        List<TimelineBucket> buckets = dashboard.timeline(2, now);

        assertThat(buckets).extracting(TimelineBucket::minute)
                .containsExactly(current.minusSeconds(60), current);
        assertThat(buckets).allSatisfy(bucket -> assertThat(bucket.counts()).isEqualTo(severities(0, 0, 0, 0)));
    }

    @Test
    void timelineCoversTheMaximumWindow() {
        Instant now = Instant.now();

        List<TimelineBucket> buckets = dashboard.timeline(120, now);

        assertThat(buckets).hasSize(120);
        assertThat(buckets.get(119).minute()).isEqualTo(now.truncatedTo(ChronoUnit.MINUTES));
        assertThat(buckets.get(0).minute()).isEqualTo(now.truncatedTo(ChronoUnit.MINUTES).minus(119,
                ChronoUnit.MINUTES));
    }

    @Test
    void recentEventsFollowTheRecentListDeduplicatedAndSkipMissingRows() {
        redis.opsForList().leftPushAll("recent:events", "EVT-1", "EVT-2", "EVT-GONE", "EVT-1", "EVT-3");
        List<IncidentEvent> rows = List.of(row("EVT-1"), row("EVT-2"), row("EVT-3"));
        when(repository.findByEventIdIn(any())).thenReturn(rows);

        List<EventResponse> recent = dashboard.recentEvents(20);

        assertThat(recent).extracting(EventResponse::eventId).containsExactly("EVT-3", "EVT-1", "EVT-2");
        verify(repository).findByEventIdIn(new LinkedHashSet<>(
                List.of("EVT-3", "EVT-1", "EVT-GONE", "EVT-2")));
    }

    @Test
    void recentEventsAreCappedAtTheLimit() {
        redis.opsForList().leftPushAll("recent:events", "EVT-1", "EVT-2", "EVT-3");
        List<IncidentEvent> rows = List.of(row("EVT-3"), row("EVT-2"));
        when(repository.findByEventIdIn(any())).thenReturn(rows);

        assertThat(dashboard.recentEvents(2)).extracting(EventResponse::eventId).containsExactly("EVT-3", "EVT-2");
        verify(repository).findByEventIdIn(new LinkedHashSet<>(List.of("EVT-3", "EVT-2")));
    }

    @Test
    void recentEventsOfEmptyListSkipPostgres() {
        assertThat(dashboard.recentEvents(20)).isEmpty();
        verify(repository, never()).findByEventIdIn(any());
    }

    private static Map<Severity, Long> severities(long info, long warning, long major, long critical) {
        long[] counts = {info, warning, major, critical};
        return Arrays.stream(Severity.values())
                .collect(Collectors.toMap(s -> s, s -> counts[s.ordinal()],
                        (a, b) -> a, () -> new EnumMap<>(Severity.class)));
    }

    private static IncidentEvent row(String eventId) {
        Instant time = Instant.parse("2026-09-27T12:30:05.123Z");
        IncidentEvent event = mock(IncidentEvent.class);
        when(event.getEventId()).thenReturn(eventId);
        when(event.getSource()).thenReturn("CBTC");
        when(event.getService()).thenReturn("signal-service");
        when(event.getSeverity()).thenReturn(Severity.INFO);
        when(event.getMessage()).thenReturn("message");
        when(event.getStatus()).thenReturn(EventStatus.OPEN);
        when(event.getTimestamp()).thenReturn(time);
        when(event.getReceivedAt()).thenReturn(time);
        when(event.getUpdatedAt()).thenReturn(time);
        return event;
    }
}
