package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Forces a real Redis outage against the full Spring wiring: ingestion and status changes keep working, dashboard
 * reads fall back to Postgres, and once Redis comes back the "redis" circuit breaker closing triggers
 * {@link LiveStateReconciler} to rebuild the live state so it matches Postgres exactly. The mutual-exclusion
 * guarantee between a rebuild and a concurrent status change (the race in the Step 4 spec) is covered deterministically
 * in {@code IncidentStatusServiceIntegrationTest} instead of here, since forcing that exact timing through a real
 * outage would make this test flaky without adding coverage.
 */
@SpringBootTest(properties = {
        "resilience4j.circuitbreaker.instances.redis.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.redis.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.redis.wait-duration-in-open-state=300ms"})
@Testcontainers
class LiveStateReconcilerIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11-alpine").withExposedPorts(6379);

    private static final String TOPIC = "incident-events";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private IncidentEventRepository repository;

    @Autowired
    private IncidentStatusService statusService;

    @Autowired
    private DashboardQueryService dashboard;

    @Autowired
    private ReconcileState reconcileState;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CircuitBreaker redisCircuitBreaker;

    @Test
    void liveStateReconcilesFromPostgresAfterARealRedisOutageAndRecovery() {
        send("EVT-1", "signal-service", "CRITICAL", "OPEN");
        send("EVT-2", "pis-service", "WARNING", "ACKNOWLEDGED");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findByEventId("EVT-2")).isPresent());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(redisTemplate.opsForValue().get("events:count")).isEqualTo("2"));

        // Paused, not stopped: freezing the container's process leaves the port mapping untouched (stopping and
        // restarting the container can lose the mapped port on some Docker setups) while still making every Redis
        // command time out, since the frozen process can no longer respond.
        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            EventResponse changed = statusService.changeStatus("EVT-1", EventStatus.ACKNOWLEDGED);
            assertThat(changed.status()).isEqualTo(EventStatus.ACKNOWLEDGED);
            assertThat(repository.findByEventId("EVT-1").orElseThrow().getStatus())
                    .isEqualTo(EventStatus.ACKNOWLEDGED);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(reconcileState.isNeeded()).isTrue());

            // Reads fall back to Postgres while Redis is genuinely unreachable, not just mocked.
            assertThat(dashboard.summary().totalEvents()).isEqualTo(2);
            assertThat(dashboard.services()).extracting(ServiceState::name)
                    .containsExactlyInAnyOrder("pis-service", "signal-service");
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }

        // The breaker only re-evaluates when something actually calls through it: a half-open probe needs real
        // traffic, same as production relies on ingestion/reads to notice Redis is back. Polling a harmless read
        // supplies that traffic here.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            try {
                dashboard.summary();
            } catch (RuntimeException stillRecovering) {
                // Ignored: the next poll probes again.
            }
            assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(reconcileState.isNeeded()).isFalse();
        });

        // The rebuild wrote absolute values computed from Postgres: EVT-1 is ACKNOWLEDGED CRITICAL (still active,
        // no longer counted as open), EVT-2 is still ACKNOWLEDGED WARNING (active, degraded).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(redisTemplate.opsForSet().members("services"))
                    .containsExactlyInAnyOrder("signal-service", "pis-service");
            assertThat(redisTemplate.opsForHash().entries("service:signal-service"))
                    .containsEntry("status", "DOWN")
                    .containsEntry("active:CRITICAL", "1")
                    .containsEntry("openCount", "0")
                    .containsEntry("activeCount", "1");
            assertThat(redisTemplate.opsForHash().entries("service:pis-service"))
                    .containsEntry("status", "DEGRADED")
                    .containsEntry("active:WARNING", "1")
                    .containsEntry("openCount", "0")
                    .containsEntry("activeCount", "1");
            assertThat(redisTemplate.opsForValue().get("status:OPEN:count")).isEqualTo("0");
            assertThat(redisTemplate.opsForValue().get("status:ACKNOWLEDGED:count")).isEqualTo("2");
        });

        // The Kafka listener resumed: a new event ingests and applies normally through the ordinary incremental path.
        send("EVT-3", "signal-service", "INFO", "OPEN");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(redisTemplate.opsForValue().get("events:count")).isEqualTo("3"));
        assertThat(redisTemplate.opsForHash().entries("service:signal-service"))
                .containsEntry("openCount", "1")
                .containsEntry("activeCount", "2")
                .containsEntry("status", "DOWN");
    }

    private void send(String eventId, String service, String severity, String status) {
        kafkaTemplate.send(TOPIC, service, """
                {"eventId":"%s","source":"CBTC","service":"%s","severity":"%s","message":"m",\
                "status":"%s","timestamp":"2026-09-26T14:30:05.123Z"}""".formatted(eventId, service, severity, status));
    }
}
