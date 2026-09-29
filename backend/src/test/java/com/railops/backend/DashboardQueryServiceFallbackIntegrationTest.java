package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Every read falls back to Postgres, with the same shape Redis would return, whenever Redis is unreachable. */
// Not transactional: separate inserts need distinct received_at values from the database clock, as in production.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class DashboardQueryServiceFallbackIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    @Autowired
    private IncidentEventRepository repository;

    private DashboardQueryService dashboard;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class,
                invocation -> { throw new RedisConnectionFailureException("Redis is down"); });
        dashboard = new DashboardQueryService(brokenRedis, repository, JSON, CircuitBreaker.ofDefaults("test"));
    }

    @Test
    void summaryIsBuiltFromPostgres() {
        insert("EVT-1", "signal-service", Severity.CRITICAL, EventStatus.OPEN, Instant.parse("2026-09-27T12:00:00Z"));
        insert("EVT-2", "signal-service", Severity.CRITICAL, EventStatus.RESOLVED, Instant.parse("2026-09-27T12:00:01Z"));
        insert("EVT-3", "pis-service", Severity.WARNING, EventStatus.ACKNOWLEDGED, Instant.parse("2026-09-27T11:59:55Z"));

        DashboardSummary summary = dashboard.summary();

        assertThat(summary.totalEvents()).isEqualTo(3);
        assertThat(summary.openEvents()).isEqualTo(1);
        assertThat(summary.acknowledgedEvents()).isEqualTo(1);
        assertThat(summary.criticalEvents()).isEqualTo(1);
        assertThat(summary.severityDistribution()).containsExactlyEntriesOf(severities(0, 1, 0, 2));
        // signal-service's EVT-1 (CRITICAL, OPEN) is still active even though EVT-2 was resolved.
        assertThat(summary.services()).containsExactly(
                new ServiceSummary("pis-service", ServiceHealth.DEGRADED, Instant.parse("2026-09-27T11:59:55Z")),
                new ServiceSummary("signal-service", ServiceHealth.DOWN, Instant.parse("2026-09-27T12:00:01Z")));
    }

    @Test
    void summaryOfEmptyPostgresIsAllZero() {
        assertThat(dashboard.summary()).isEqualTo(new DashboardSummary(0, 0, 0, 0, severities(0, 0, 0, 0), List.of()));
    }

    @Test
    void servicesAreBuiltFromPostgresSortedByNameWithHealthDerivedTheSameWayRedisDoes() {
        insert("EVT-1", "signal-service", Severity.MAJOR, EventStatus.OPEN, Instant.parse("2026-09-27T12:30:05Z"));
        insert("EVT-2", "signal-service", Severity.INFO, EventStatus.ACKNOWLEDGED, Instant.parse("2026-09-27T12:30:06Z"));
        insert("EVT-3", "ats-service", Severity.INFO, EventStatus.RESOLVED, Instant.parse("2026-09-27T12:30:05Z"));

        assertThat(dashboard.services()).containsExactly(
                new ServiceState("ats-service", ServiceHealth.HEALTHY, Instant.parse("2026-09-27T12:30:05Z"),
                        Severity.INFO, 0, 0),
                new ServiceState("signal-service", ServiceHealth.DEGRADED, Instant.parse("2026-09-27T12:30:06Z"),
                        Severity.INFO, 1, 2));
    }

    @Test
    void aServiceWithOnlyAnActiveCriticalEventIsDown() {
        insert("EVT-1", "signal-service", Severity.CRITICAL, EventStatus.OPEN, Instant.now());

        assertThat(dashboard.services()).extracting(ServiceState::status).containsExactly(ServiceHealth.DOWN);
    }

    @Test
    void timelineIsZeroFilledOldestFirstAndBucketedByUtcMinute() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
        Instant current = now.truncatedTo(ChronoUnit.MINUTES);
        insert("EVT-1", "signal-service", Severity.CRITICAL, EventStatus.OPEN, current.plusSeconds(1));
        insert("EVT-2", "signal-service", Severity.MAJOR, EventStatus.OPEN, current.plusSeconds(2));
        insert("EVT-3", "signal-service", Severity.MAJOR, EventStatus.OPEN, current.minusSeconds(120));

        List<TimelineBucket> buckets = dashboard.timeline(3, now);

        assertThat(buckets).containsExactly(
                new TimelineBucket(current.minusSeconds(120), severities(0, 0, 1, 0)),
                new TimelineBucket(current.minusSeconds(60), severities(0, 0, 0, 0)),
                new TimelineBucket(current, severities(0, 0, 1, 1)));
    }

    @Test
    void recentEventsAreOrderedNewestReceivedFirst() {
        insert("EVT-1", "signal-service", Severity.INFO, EventStatus.OPEN, Instant.parse("2026-09-27T12:00:00Z"));
        insert("EVT-2", "signal-service", Severity.INFO, EventStatus.OPEN, Instant.parse("2026-09-27T12:00:01Z"));
        insert("EVT-3", "signal-service", Severity.INFO, EventStatus.OPEN, Instant.parse("2026-09-27T12:00:02Z"));

        assertThat(dashboard.recentEvents(2)).extracting(EventResponse::eventId).containsExactly("EVT-3", "EVT-2");
    }

    @Test
    void recentEventsOfEmptyPostgresIsEmpty() {
        assertThat(dashboard.recentEvents(20)).isEmpty();
    }

    private void insert(String eventId, String service, Severity severity, EventStatus status, Instant timestamp) {
        repository.insertIfAbsent(eventId, "CBTC", service, severity.name(), "message", status.name(), timestamp);
    }

    private static Map<Severity, Long> severities(long info, long warning, long major, long critical) {
        long[] counts = {info, warning, major, critical};
        return Arrays.stream(Severity.values())
                .collect(Collectors.toMap(s -> s, s -> counts[s.ordinal()],
                        (a, b) -> a, () -> new EnumMap<>(Severity.class)));
    }
}
