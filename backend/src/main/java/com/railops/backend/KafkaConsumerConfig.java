package com.railops.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Invalid records are logged and skipped; everything else (for example Postgres being down) is retried until it
 * succeeds, so a valid event is never dropped. Boot applies this handler to the listener container factory.
 */
@Configuration
class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    private static final long RETRY_INTERVAL_MS = 2000;
    static final int MAX_REASON_LENGTH = 200;

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(KafkaConsumerConfig::skip,
                new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
        // DeserializationException is already non-retryable by default.
        handler.addNotRetryableExceptions(InvalidEventException.class, DataIntegrityViolationException.class);
        // Commit the skipped record's offset even when no later record on the partition would (MANUAL_IMMEDIATE).
        handler.setCommitRecovered(true);
        // Every failed attempt is logged with a short reason, so outages stay visible without logging row data.
        // Invalid records fail once and are then skipped.
        handler.setRetryListeners((record, exception, attempt) -> log.warn(
                "Failed to process event at {}-{}@{} (attempt {}): {}", record.topic(), record.partition(), record.offset(),
                attempt, reason(exception)));
        return handler;
    }

    private static void skip(ConsumerRecord<?, ?> record, Exception exception) {
        log.warn("Skipping invalid event at {}-{}@{}: {}", record.topic(), record.partition(), record.offset(),
                reason(exception));
    }

    /** A short reason that never echoes payload values. */
    static String reason(Throwable exception) {
        String reason = exception.getClass().getSimpleName();
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof InvalidEventException invalid) {
                reason = invalid.getMessage();
                break;
            }
            if (t instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
                reason = "unreadable payload: invalid value for " + mapping.getPath().stream()
                        .map(JsonMappingException.Reference::getFieldName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("."));
                break;
            }
            if (t instanceof JsonProcessingException) {
                reason = "unreadable payload: not valid event JSON";
                break;
            }
            if (t instanceof SQLException sql) {
                // The driver message can include the failing row, so report only the SQL state.
                reason = "database error (SQLState " + sql.getSQLState() + ")";
                break;
            }
        }
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) + "..." : reason;
    }
}
