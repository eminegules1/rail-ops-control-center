package com.railops.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Invalid records go straight to the dead-letter topic. Everything else (for example PostgreSQL being down) is
 * retried with exponential back-off and goes to the dead-letter topic once the retries run out, so a record is
 * never silently dropped. Boot applies this handler to the listener container factory.
 */
@Configuration
class KafkaConsumerConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    static final int MAX_REASON_LENGTH = 200;

    private DefaultKafkaProducerFactory<String, Object> deadLetterProducerFactory;

    @Bean
    DefaultErrorHandler kafkaErrorHandler(IngestionProperties properties, ProducerFactory<?, ?> producerFactory) {
        deadLetterProducerFactory = deadLetterProducerFactory(producerFactory);
        return errorHandler(properties, new KafkaTemplate<>(deadLetterProducerFactory));
    }

    /** The handler with the dead-letter sender passed in, so tests can make the send fail. */
    static DefaultErrorHandler errorHandler(IngestionProperties properties, KafkaOperations<?, ?> deadLetterTemplate) {
        String deadLetterTopic = properties.deadLetter().name();
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            // Throws if Kafka does not confirm the send; the record is then attempted again, never skipped.
            publisher.accept(record, exception);
            log.warn("Sent event at {}-{}@{} to {}: {}", record.topic(), record.partition(), record.offset(),
                    deadLetterTopic, reason(exception));
        }, backOff(properties.retry()));
        // DeserializationException is already non-retryable by default.
        handler.addNotRetryableExceptions(InvalidEventException.class, DataIntegrityViolationException.class);
        // Commit the recovered record's offset even when no later record on the partition would (MANUAL_IMMEDIATE).
        handler.setCommitRecovered(true);
        // Every failed attempt is logged with a short reason, so outages stay visible without logging row data.
        // Invalid records fail once and then go to the dead-letter topic.
        handler.setRetryListeners((record, exception, attempt) -> log.warn(
                "Failed to process event at {}-{}@{} (attempt {}): {}", record.topic(), record.partition(), record.offset(),
                attempt, reason(exception)));
        return handler;
    }

    static ExponentialBackOffWithMaxRetries backOff(IngestionProperties.Retry retry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    /** Closes the dead-letter producer with the context, since it is not a bean Spring would destroy itself. */
    @Override
    public void destroy() {
        if (deadLetterProducerFactory != null) {
            deadLetterProducerFactory.destroy();
        }
    }

    /**
     * A record that could not be deserialized is published as its original bytes; one that was read but then
     * failed is published as event JSON. Not a bean: a KafkaTemplate or ProducerFactory bean would replace Boot's.
     */
    private static DefaultKafkaProducerFactory<String, Object> deadLetterProducerFactory(
            ProducerFactory<?, ?> producerFactory) {
        // Same wire format as the producer: ISO-8601 timestamp, no type headers.
        JsonSerializer<Object> json = new JsonSerializer<>(JacksonUtils.enhancedObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        json.setAddTypeInfo(false);
        DelegatingByTypeSerializer values = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                IncidentEventMessage.class, json));
        return new DefaultKafkaProducerFactory<>(producerFactory.getConfigurationProperties(), new StringSerializer(),
                values);
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
