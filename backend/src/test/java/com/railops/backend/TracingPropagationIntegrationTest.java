package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A record that arrives with a W3C {@code traceparent} header continues that trace in the backend: the consumer span
 * is a child of the sender's span, and the log lines written while the event is handled carry its {@code traceId}.
 * OTLP export is off; spans are read from an in-memory exporter.
 */
@SpringBootTest(properties = "management.otlp.tracing.export.enabled=false")
@AutoConfigureObservability
@Testcontainers
class TracingPropagationIntegrationTest {

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
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SENDER_SPAN_ID = "00f067aa0ba902b7";
    // Not a bean: a SpanExporter bean would also be wrapped in Boot's batching processor and capture each span twice.
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @TestConfiguration
    static class CaptureSpans {
        @Bean
        SpanProcessor inMemorySpanProcessor() {
            return SimpleSpanProcessor.create(SPANS);
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ListAppender<ILoggingEvent> ingestionLogs = new ListAppender<>();
    private ch.qos.logback.classic.Logger ingestionLogger;

    @BeforeEach
    void captureLogs() {
        ingestionLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EventIngestionService.class);
        ingestionLogs.start();
        ingestionLogger.addAppender(ingestionLogs);
    }

    @AfterEach
    void releaseLogs() {
        ingestionLogger.detachAppender(ingestionLogs);
    }

    @Test
    void consumerSpanContinuesTheSendersTraceAndLogsCarryItsTraceId() throws Exception {
        String payload = """
                {"eventId":"TRACE-1","source":"CBTC","service":"tracing-service","severity":"INFO","message":"traced",\
                "status":"OPEN","timestamp":"2026-09-26T14:30:05.123Z"}""";
        // The second delivery is a duplicate, which is logged at INFO ("Duplicate event ... skipped").
        send(payload);
        send(payload);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(SPANS.getFinishedSpanItems())
                    .filteredOn(span -> span.getKind() == SpanKind.CONSUMER && TRACE_ID.equals(span.getTraceId()))
                    .isNotEmpty()
                    .allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(SENDER_SPAN_ID));
            assertThat(ingestionLogs.list)
                    .filteredOn(log -> log.getFormattedMessage().contains("Duplicate event TRACE-1 skipped"))
                    .isNotEmpty()
                    .allSatisfy(log -> assertThat(log.getMDCPropertyMap()).containsEntry("traceId", TRACE_ID));
        });
        assertThat(SPANS.getFinishedSpanItems()).extracting(SpanData::getTraceId).contains(TRACE_ID);
    }

    private void send(String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "tracing-service", payload);
        record.headers().add("traceparent",
                ("00-" + TRACE_ID + "-" + SENDER_SPAN_ID + "-01").getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();
    }
}
