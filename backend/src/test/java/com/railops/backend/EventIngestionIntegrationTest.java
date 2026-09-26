package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Testcontainers
class EventIngestionIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    private static final String TOPIC = "incident-events";
    private static final String KEY = "signal-service";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private IncidentEventRepository repository;

    // Ends on invalid records (the last a tombstone), so reaching the end offset proves that skipped records'
    // offsets are committed, not just covered by a later valid record's ack.
    @Test
    void storesValidEventsOnceAndSkipsInvalidOnes() throws Exception {
        String first = event("EVT-1", "CBTC", KEY, "CRITICAL", "\"EVT-1 message\"");
        List<String> payloads = List.of(
                first,
                first,
                "{not json",
                event("EVT-2", "CBTC", KEY, "critical", "\"wrong-case severity\""),
                event(" ", "CBTC", KEY, "INFO", "\"blank id\""),
                event("EVT-3", "CBTC", "s".repeat(100), "INFO", "\"long service\""),
                event("EVT-4", "XYZ", KEY, "INFO", "\"unknown source\""),
                event("EVT-6", "CBTC", KEY, "3", "\"enum ordinal\""),
                event("EVT-5", "ATS", KEY, "WARNING", "\"second valid\""),
                "[]");

        int partition = -1;
        for (String payload : payloads) {
            partition = kafkaTemplate.send(TOPIC, KEY, payload).get().getRecordMetadata().partition();
        }
        kafkaTemplate.send(TOPIC, KEY, null).get();
        TopicPartition topicPartition = new TopicPartition(TOPIC, partition);

        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(repository.findAll())
                        .extracting(IncidentEvent::getEventId)
                        .containsExactlyInAnyOrder("EVT-1", "EVT-5");
                assertThat(committedOffset(admin, topicPartition)).isEqualTo(endOffset(admin, topicPartition));
            });
        }
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

    private static String event(String eventId, String source, String service, String severity, String message) {
        return """
                {"eventId":"%s","source":"%s","service":"%s","severity":"%s","message":%s,\
                "status":"OPEN","timestamp":"2026-09-26T14:30:05.123Z"}""".formatted(eventId, source, service,
                severity, message);
    }
}
