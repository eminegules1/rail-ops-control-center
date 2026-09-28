package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** A failure that is not an invalid payload is retried, then dead-lettered, and the partition moves on. */
@SpringBootTest(properties = {
        "ingestion.retry.initial-interval=50ms",
        "ingestion.retry.max-interval=100ms",
        "ingestion.retry.max-retries=2"})
@Testcontainers
class EventRetryIntegrationTest {

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
    private static final String KEY = "signal-service";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private IncidentEventRepository repository;

    @MockitoSpyBean
    private EventIngestionService ingestionService;

    // Ends on a failing record, so reaching the end offset proves the retry-exhausted record's offset is committed,
    // not just covered by the valid record's ack.
    @Test
    void retriesATransientFailureThenDeadLettersItAndKeepsConsuming() throws Exception {
        doThrow(new IllegalStateException("simulated outage")).when(ingestionService)
                .ingest(argThat(event -> event != null && event.eventId().startsWith("EVT-FAIL")));

        List<String> failing = List.of(event("EVT-FAIL-1"), event("EVT-FAIL-2"));
        int partition = kafkaTemplate.send(TOPIC, KEY, failing.get(0)).get().getRecordMetadata().partition();
        kafkaTemplate.send(TOPIC, KEY, event("EVT-OK")).get();
        kafkaTemplate.send(TOPIC, KEY, failing.get(1)).get();
        TopicPartition topicPartition = new TopicPartition(TOPIC, partition);

        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(repository.findByEventId("EVT-OK")).isPresent();
                assertThat(committedOffset(admin, topicPartition)).isEqualTo(endOffset(admin, topicPartition));
            });
        }
        assertThat(repository.findByEventId("EVT-FAIL-1")).isEmpty();
        assertThat(repository.findByEventId("EVT-FAIL-2")).isEmpty();
        // The first attempt plus max-retries, for each failing record.
        verify(ingestionService, times(3)).ingest(argThat(event -> "EVT-FAIL-1".equals(event.eventId())));
        verify(ingestionService, times(3)).ingest(argThat(event -> "EVT-FAIL-2".equals(event.eventId())));

        List<ConsumerRecord<String, byte[]>> deadLetters = DeadLetters.read(kafka.getBootstrapServers(), 2);
        assertThat(deadLetters).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(KEY);
            assertThat(record.partition()).isEqualTo(partition);
            assertThat(record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN).value()).asString()
                    .isEqualTo(IllegalStateException.class.getName());
        });
        assertThat(deadLetters).extracting(r -> new String(r.value(), StandardCharsets.UTF_8))
                .containsExactlyElementsOf(failing);
    }

    private static long committedOffset(AdminClient admin, TopicPartition partition) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets("incident-processor")
                .partitionsToOffsetAndMetadata().get();
        OffsetAndMetadata committed = offsets.get(partition);
        return committed == null ? -1 : committed.offset();
    }

    private static long endOffset(AdminClient admin, TopicPartition partition) throws Exception {
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).partitionResult(partition).get().offset();
    }

    private static String event(String eventId) {
        return """
                {"eventId":"%s","source":"CBTC","service":"%s","severity":"MAJOR","message":"m",\
                "status":"OPEN","timestamp":"2026-09-26T14:30:05.123Z"}""".formatted(eventId, KEY);
    }
}
