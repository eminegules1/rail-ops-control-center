package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.containers.GenericContainer;
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

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Ends on invalid records (the last a tombstone), so reaching the end offset proves that dead-lettered records'
    // offsets are committed, not just covered by a later valid record's ack.
    @Test
    void storesValidEventsOnceAndSendsInvalidOnesToTheDeadLetterTopic() throws Exception {
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
                // Passes validation, but Postgres rejects the NUL byte: dead-lettered, not retried.
                event("EVT-7", "CBTC", KEY, "INFO", "\"a\\u0000b\""),
                event("EVT-5", "ATS", KEY, "WARNING", "\"second valid\""),
                "[]");

        int lastPartition = -1;
        for (String payload : payloads) {
            lastPartition = kafkaTemplate.send(TOPIC, KEY, payload).get().getRecordMetadata().partition();
        }
        int partition = lastPartition;
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

        // The duplicate EVT-1 is applied to the live state once.
        assertThat(redisTemplate.opsForValue().get("events:count")).isEqualTo("2");
        assertThat(redisTemplate.hasKey("processed:EVT-1")).isTrue();
        assertThat(redisTemplate.opsForList().range("recent:events", 0, -1)).containsExactly("EVT-5", "EVT-1");
        assertThat(redisTemplate.opsForHash().entries("service:" + KEY))
                .containsEntry("status", "DOWN")
                .containsEntry("active:CRITICAL", "1")
                .containsEntry("active:WARNING", "1")
                .containsEntry("openCount", "2");

        List<ConsumerRecord<String, byte[]>> deadLetters = DeadLetters.read(kafka.getBootstrapServers(), 9);
        assertThat(deadLetters).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(KEY);
            assertThat(record.partition()).isEqualTo(partition);
            assertThat(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value()).asString().isEqualTo(TOPIC);
            assertThat(record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
        });
        // Unreadable payloads keep their original bytes; payloads that were read are published as event JSON.
        assertThat(deadLetters).extracting(r -> r.value() == null ? null : new String(r.value(), StandardCharsets.UTF_8))
                .containsExactly(
                        "{not json",
                        payloads.get(3),
                        event(" ", "CBTC", KEY, "INFO", "\"blank id\""),
                        event("EVT-3", "CBTC", "s".repeat(100), "INFO", "\"long service\""),
                        event("EVT-4", "XYZ", KEY, "INFO", "\"unknown source\""),
                        payloads.get(7),
                        payloads.get(8),
                        "[]",
                        null);
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
