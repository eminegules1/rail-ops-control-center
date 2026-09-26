package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;

class KafkaConsumerConfigTest {

    private final IncidentEventDeserializer deserializer = new IncidentEventDeserializer();

    @Test
    void readsProducerPayloadAndIgnoresUnknownFields() {
        IncidentEventMessage event = deserializer.deserialize("t", bytes("""
                {"eventId":"EVT-1","source":"CBTC","service":"signal-service","severity":"CRITICAL",                "message":"m","status":"OPEN","timestamp":"2026-09-26T16:30:05.123+02:00","extra":1}"""));

        assertThat(event.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-09-26T14:30:05.123Z"));
    }

    @Test
    void rejectsEnumOrdinals() {
        assertThat(reasonFor("""
                {"eventId":"EVT-1","severity":3}""")).isEqualTo("unreadable payload: invalid value for severity");
        assertThat(reasonFor("""
                {"eventId":"EVT-1","status":"1"}""")).isEqualTo("unreadable payload: invalid value for status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1790000000", "\"1790000000\"", "\"1790000000.5\"", "\"2026-09-26T14:30:05\""})
    void rejectsTimestampsThatAreNotIsoInstants(String timestamp) {
        assertThat(reasonFor("""
                {"eventId":"EVT-1","timestamp":%s}""".formatted(timestamp)))
                .isEqualTo("unreadable payload: invalid value for timestamp");
    }

    @Test
    void usesInvalidEventMessageFromCauseChain() {
        Exception wrapped = new ListenerExecutionFailedException("listener failed",
                new InvalidEventException("invalid fields: eventId, source"));

        assertThat(KafkaConsumerConfig.reason(wrapped)).isEqualTo("invalid fields: eventId, source");
    }

    @Test
    void namesFieldPathButNotValueForUnknownEnum() {
        Exception parse = parseFailure("""
                {"eventId":"EVT-1","severity":"top-secret-value"}""");

        String reason = KafkaConsumerConfig.reason(deserializationFailure(parse));

        assertThat(reason).isEqualTo("unreadable payload: invalid value for severity");
        assertThat(reason).doesNotContain("top-secret-value");
    }

    @Test
    void reportsMalformedJsonWithoutEchoingIt() {
        Exception parse = parseFailure("{secret-token");

        String reason = KafkaConsumerConfig.reason(deserializationFailure(parse));

        assertThat(reason).isEqualTo("unreadable payload: not valid event JSON");
    }

    @Test
    void reportsOnlySqlStateForDatabaseErrors() {
        SQLException sql = new SQLException("ERROR: violates check\n  Detail: Failing row contains (secret)", "23514");
        Exception wrapped = new ListenerExecutionFailedException("listener failed",
                new DataIntegrityViolationException("could not execute statement", sql));

        String reason = KafkaConsumerConfig.reason(wrapped);

        assertThat(reason).isEqualTo("database error (SQLState 23514)");
        assertThat(reason).doesNotContain("secret");
    }

    @Test
    void fallsBackToExceptionTypeName() {
        assertThat(KafkaConsumerConfig.reason(new IllegalStateException("details with secret")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void capsLongReasons() {
        String reason = KafkaConsumerConfig.reason(new InvalidEventException("x".repeat(500)));

        assertThat(reason).hasSize(KafkaConsumerConfig.MAX_REASON_LENGTH + 3).endsWith("...");
    }

    private String reasonFor(String json) {
        return KafkaConsumerConfig.reason(deserializationFailure(parseFailure(json)));
    }

    private Exception parseFailure(String json) {
        return (Exception) catchThrowable(() -> deserializer.deserialize("t", bytes(json)));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Exception deserializationFailure(Exception cause) {
        return new ListenerExecutionFailedException("listener failed",
                new DeserializationException("failed to deserialize", new byte[0], false, cause));
    }
}
