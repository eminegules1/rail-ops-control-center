package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole pipeline through its real entry points: events go in through Kafka and the status API, and are observed
 * only through HTTP, the Prometheus endpoint and the dead-letter topic. The tests share one application context, one
 * metric registry and the same topics, so each uses its own service name and event id prefix and reads counters as
 * before/after deltas; none depends on the order the others run in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.circuitbreaker.instances.redis.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.redis.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.redis.wait-duration-in-open-state=300ms"})
@AutoConfigureObservability
@Testcontainers
class EndToEndIntegrationTest {

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
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CircuitBreaker redisCircuitBreaker;

    @Autowired
    private ReconcileState reconcileState;

    @BeforeEach
    void signIn() {
        TestAuth.signInAsAdmin(rest);
    }

    @Test
    void happyPathEventsFlowFromKafkaToTheApiAndStatusChangesAreReflected() throws Exception {
        String service = "e2e-happy-service";
        double processedBefore = counter("processed");

        send(service, "E2E-H-1", "CRITICAL", "OPEN", "2026-09-26T10:00:00Z");
        send(service, "E2E-H-2", "INFO", "OPEN", "2026-09-26T10:00:01Z");
        send(service, "E2E-H-3", "WARNING", "ACKNOWLEDGED", "2026-09-26T10:00:02Z");

        await().atMost(WAIT).untilAsserted(() -> assertThat(events(service).totalElements()).isEqualTo(3));
        assertThat(events(service).content()).extracting(EventResponse::eventId)
                .containsExactly("E2E-H-3", "E2E-H-2", "E2E-H-1");
        EventResponse first = rest.getForObject("/api/events/E2E-H-1", EventResponse.class);
        assertThat(first.service()).isEqualTo(service);
        assertThat(first.source()).isEqualTo("CBTC");
        assertThat(first.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(first.status()).isEqualTo(EventStatus.OPEN);
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(counter("processed") - processedBefore).isEqualTo(3));

        await().atMost(WAIT).untilAsserted(() -> assertThat(serviceState(service)).isEqualTo(
                new ServiceState(service, ServiceHealth.DOWN, Instant.parse("2026-09-26T10:00:02Z"),
                        Severity.WARNING, 2, 3)));
        assertThat(rest.getForObject("/api/dashboard/recent-events", EventResponse[].class))
                .extracting(EventResponse::eventId)
                .filteredOn(id -> id.startsWith("E2E-H-"))
                .containsExactly("E2E-H-3", "E2E-H-2", "E2E-H-1");
        awaitSummaryMatchesEventsApi(service, ServiceHealth.DOWN);

        assertStatusChange("E2E-H-1", EventStatus.ACKNOWLEDGED);
        await().atMost(WAIT).untilAsserted(() -> {
            ServiceState state = serviceState(service);
            assertThat(state.status()).isEqualTo(ServiceHealth.DOWN);
            assertThat(state.openCount()).isEqualTo(1);
            assertThat(state.activeCount()).isEqualTo(3);
        });
        assertStatusChange("E2E-H-1", EventStatus.RESOLVED);
        // The only remaining active event above INFO is the WARNING one, so the service is degraded, not down.
        await().atMost(WAIT).untilAsserted(() -> {
            ServiceState state = serviceState(service);
            assertThat(state.status()).isEqualTo(ServiceHealth.DEGRADED);
            assertThat(state.openCount()).isEqualTo(1);
            assertThat(state.activeCount()).isEqualTo(2);
        });
        awaitSummaryMatchesEventsApi(service, ServiceHealth.DEGRADED);

        ResponseEntity<String> invalid = rest.exchange("/api/events/E2E-H-1/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "ACKNOWLEDGED")), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Redelivering the resolved event changes nothing; the later event proves the duplicate was consumed first.
        send(service, "E2E-H-1", "CRITICAL", "OPEN", "2026-09-26T10:00:00Z");
        send(service, "E2E-H-4", "INFO", "OPEN", "2026-09-26T10:00:03Z");
        await().atMost(WAIT).untilAsserted(() -> assertThat(events(service).totalElements()).isEqualTo(4));
        assertThat(events(service).content()).extracting(EventResponse::eventId)
                .containsExactlyInAnyOrder("E2E-H-1", "E2E-H-2", "E2E-H-3", "E2E-H-4");
        assertThat(rest.getForObject("/api/events/E2E-H-1", EventResponse.class).status())
                .isEqualTo(EventStatus.RESOLVED);
    }

    @Test
    void invalidRecordsReachTheDeadLetterTopicAndDoNotBlockValidEvents() throws Exception {
        String service = "e2e-dlt-service";
        double processedBefore = counter("processed");
        double invalidBefore = counter("invalid");
        double dltBefore = counter("dlt");

        send(service, "E2E-D-1", "INFO", "OPEN", "2026-09-26T11:00:00Z");
        kafkaTemplate.send(TOPIC, service, "{not json").get();
        send(service, "E2E-D-BAD", "critical", "OPEN", "2026-09-26T11:00:01Z");
        send(service, "E2E-D-2", "MAJOR", "OPEN", "2026-09-26T11:00:02Z");

        await().atMost(WAIT).untilAsserted(() -> assertThat(events(service).totalElements()).isEqualTo(2));
        assertThat(events(service).content()).extracting(EventResponse::eventId)
                .containsExactly("E2E-D-2", "E2E-D-1");
        assertThat(rest.getForEntity("/api/events/E2E-D-BAD", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        List<ConsumerRecord<String, byte[]>> deadLetters = DeadLetters.read(kafka.getBootstrapServers(), 2).stream()
                .filter(record -> service.equals(record.key()))
                .toList();
        assertThat(deadLetters).extracting(record -> new String(record.value(), StandardCharsets.UTF_8))
                .hasSize(2)
                .first().isEqualTo("{not json");
        assertThat(new String(deadLetters.get(1).value(), StandardCharsets.UTF_8)).contains("E2E-D-BAD");

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(counter("processed") - processedBefore).isEqualTo(2);
            assertThat(counter("invalid") - invalidBefore).isEqualTo(2);
            assertThat(counter("dlt") - dltBefore).isEqualTo(2);
        });
        awaitSummaryMatchesEventsApi(service, ServiceHealth.DEGRADED);
    }

    @Test
    void apiKeepsAnsweringFromPostgresWhileRedisIsDownAndConvergesAfterItReturns() throws Exception {
        String service = "e2e-redis-service";
        send(service, "E2E-R-1", "CRITICAL", "OPEN", "2026-09-26T12:00:00Z");
        send(service, "E2E-R-2", "WARNING", "OPEN", "2026-09-26T12:00:01Z");
        await().atMost(WAIT).untilAsserted(() -> {
            ServiceState state = serviceState(service);
            assertThat(state).isNotNull();
            assertThat(state.openCount()).isEqualTo(2);
            assertThat(state.status()).isEqualTo(ServiceHealth.DOWN);
        });

        // Paused, not stopped, as in LiveStateReconcilerIntegrationTest: the port mapping survives and every Redis
        // command times out.
        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            ResponseEntity<DashboardSummary> summary = rest.getForEntity("/api/dashboard/summary",
                    DashboardSummary.class);
            assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(summary.getBody().totalEvents()).isEqualTo(events(null).totalElements());
            assertThat(summary.getBody().services()).extracting(ServiceSummary::name).contains(service);
            assertThat(servicesResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(serviceState(service).openCount()).isEqualTo(2);

            assertStatusChange("E2E-R-1", EventStatus.ACKNOWLEDGED);
            ServiceState duringOutage = serviceState(service);
            assertThat(duringOutage.status()).isEqualTo(ServiceHealth.DOWN);
            assertThat(duringOutage.openCount()).isEqualTo(1);
            assertThat(duringOutage.activeCount()).isEqualTo(2);
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }

        // A half-open probe needs real traffic through the breaker; polling a read supplies it.
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            rest.getForEntity("/api/dashboard/summary", DashboardSummary.class);
            assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(reconcileState.isNeeded()).isFalse();
        });
        await().atMost(WAIT).untilAsserted(() -> {
            ServiceState recovered = serviceState(service);
            assertThat(recovered.status()).isEqualTo(ServiceHealth.DOWN);
            assertThat(recovered.openCount()).isEqualTo(1);
            assertThat(recovered.activeCount()).isEqualTo(2);
            // The HTTP values match during the outage too, so the rebuilt Redis hash is what proves recovery.
            assertThat(redisTemplate.opsForHash().entries("service:" + service))
                    .containsEntry("openCount", "1")
                    .containsEntry("activeCount", "2");
        });
        assertThat(rest.getForObject("/api/dashboard/summary", DashboardSummary.class).totalEvents())
                .isEqualTo(events(null).totalElements());
    }

    private void send(String service, String eventId, String severity, String status, String timestamp)
            throws Exception {
        kafkaTemplate.send(TOPIC, service, """
                {"eventId":"%s","source":"CBTC","service":"%s","severity":"%s","message":"%s message",\
                "status":"%s","timestamp":"%s"}""".formatted(eventId, service, severity, eventId, status, timestamp))
                .get();
    }

    private void assertStatusChange(String eventId, EventStatus status) {
        ResponseEntity<EventResponse> response = rest.exchange("/api/events/" + eventId + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", status.name())), EventResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(status);
        assertThat(rest.getForObject("/api/events/" + eventId, EventResponse.class).status()).isEqualTo(status);
    }

    /** A null service lists every event; totalElements then counts all of Postgres. */
    private EventPageResponse events(String service) {
        String url = service == null ? "/api/events?size=1" : "/api/events?size=100&service=" + service;
        return rest.getForObject(url, EventPageResponse.class);
    }

    private ResponseEntity<ServiceState[]> servicesResponse() {
        return rest.getForEntity("/api/services", ServiceState[].class);
    }

    private ServiceState serviceState(String service) {
        ResponseEntity<ServiceState[]> response = servicesResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Arrays.stream(response.getBody()).filter(s -> s.name().equals(service)).findFirst()
                .orElse(null);
    }

    /** The summary is a cache of the same data the events API serves, so its total converges on Postgres's count. */
    private void awaitSummaryMatchesEventsApi(String service, ServiceHealth expected) {
        await().atMost(WAIT).untilAsserted(() -> {
            DashboardSummary summary = rest.getForObject("/api/dashboard/summary", DashboardSummary.class);
            assertThat(summary.totalEvents()).isEqualTo(events(null).totalElements());
            assertThat(summary.services()).filteredOn(s -> s.name().equals(service))
                    .extracting(ServiceSummary::status).containsExactly(expected);
        });
    }

    private double counter(String outcome) {
        Pattern pattern = Pattern.compile(
                "^ingestion_events_total\\{[^}]*outcome=\"" + outcome + "\"[^}]*}\\s+(\\S+)$", Pattern.MULTILINE);
        String body = rest.getForObject("/actuator/prometheus", String.class);
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }
}
