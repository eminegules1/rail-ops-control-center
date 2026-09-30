package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

// A real STOMP client against the running server: Kafka ingestion and the status API push to subscribers.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LiveUpdatesIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11-alpine").withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    private final List<StompSession> sessions = new ArrayList<>();

    private String adminToken;

    @BeforeEach
    void signIn() {
        TestAuth.signInAsAdmin(rest);
        adminToken = TestAuth.login(rest, "admin", "RailOps#Admin2026");
    }

    @AfterEach
    void disconnect() {
        for (StompSession session : sessions) {
            try {
                session.disconnect();
            } catch (IllegalStateException e) {
                // The server already closed it, as it does after sending a client an ERROR frame.
            }
        }
    }

    @Test
    void pushesIngestedEventStatusChangeAndSummary() throws Exception {
        StompSession session = connect();
        BlockingQueue<JsonNode> events = subscribe(session, "/topic/events");
        BlockingQueue<JsonNode> summaries = subscribe(session, "/topic/summary");

        kafkaTemplate.send("incident-events", "signal-service", """
                {"eventId":"EVT-WS-1","source":"CBTC","service":"signal-service","severity":"CRITICAL",\
                "message":"Signal failure","status":"OPEN","timestamp":"2026-09-26T10:00:00Z"}""").get();

        JsonNode created = events.poll(60, TimeUnit.SECONDS);
        assertThat(created).isNotNull();
        assertThat(created.path("type").asText()).isEqualTo("CREATED");
        assertThat(created.path("event").path("eventId").asText()).isEqualTo("EVT-WS-1");
        assertThat(created.path("event").path("status").asText()).isEqualTo("OPEN");
        // Same shape as the REST API: ISO-8601 strings, not epoch numbers.
        assertThat(created.path("event").path("timestamp").isTextual()).isTrue();
        assertThat(created.path("event").path("receivedAt").isTextual()).isTrue();

        var response = rest.exchange("/api/events/EVT-WS-1/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "ACKNOWLEDGED")), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode updated = events.poll(10, TimeUnit.SECONDS);
        assertThat(updated).isNotNull();
        assertThat(updated.path("type").asText()).isEqualTo("UPDATED");
        assertThat(updated.path("event").path("eventId").asText()).isEqualTo("EVT-WS-1");
        assertThat(updated.path("event").path("status").asText()).isEqualTo("ACKNOWLEDGED");

        JsonNode summary = summaries.poll(10, TimeUnit.SECONDS);
        assertThat(summary).isNotNull();
        assertThat(summary.path("totalEvents").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void clientSendNeverReachesSubscribers() throws Exception {
        BlockingQueue<JsonNode> events = subscribe(connect(), "/topic/events");
        BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();
        StompSession forger = connect(new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(headers);
            }
        });

        forger.send("/topic/events", Map.of("type", "CREATED", "forged", true));

        assertThat(errors.poll(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(events.poll(2, TimeUnit.SECONDS)).isNull();
    }

    // The same-origin check is the only browser-facing guard on /ws; a client without an Origin is always accepted.
    @Test
    void refusesHandshakeFromAnotherOrigin() {
        assertThatThrownBy(() -> connect(origin("http://evil.example")))
                .isInstanceOf(ExecutionException.class)
                .hasStackTraceContaining("403");
    }

    @Test
    void acceptsHandshakeFromTheServersOwnOrigin() throws Exception {
        assertThat(connect(origin("http://localhost:" + port)).isConnected()).isTrue();
    }

    @Test
    void refusesAConnectWithoutALoginToken() throws Exception {
        BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();

        assertThatThrownBy(() -> connect(new WebSocketHttpHeaders(), new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(headers);
            }
        }, null)).isInstanceOf(ExecutionException.class);

        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void refusesAConnectWithAnInvalidLoginToken() {
        assertThatThrownBy(() -> connect(new WebSocketHttpHeaders(), new StompSessionHandlerAdapter() {
        }, "not.a.jwt")).isInstanceOf(ExecutionException.class);
    }

    private static WebSocketHttpHeaders origin(String origin) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return headers;
    }

    private StompSession connect() throws Exception {
        return connect(new StompSessionHandlerAdapter() {
        });
    }

    private StompSession connect(WebSocketHttpHeaders headers) throws Exception {
        return connect(headers, new StompSessionHandlerAdapter() {
        });
    }

    private StompSession connect(StompSessionHandlerAdapter handler) throws Exception {
        return connect(new WebSocketHttpHeaders(), handler);
    }

    private StompSession connect(WebSocketHttpHeaders headers, StompSessionHandlerAdapter handler) throws Exception {
        return connect(headers, handler, adminToken);
    }

    private StompSession connect(WebSocketHttpHeaders headers, StompSessionHandlerAdapter handler, String token)
            throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(json);
        client.setMessageConverter(converter);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", headers, connectHeaders, handler)
                .get(10, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private static BlockingQueue<JsonNode> subscribe(StompSession session, String topic) {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        return received;
    }
}
