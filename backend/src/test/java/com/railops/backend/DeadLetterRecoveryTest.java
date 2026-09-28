package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

/**
 * Drives the app's error handler with a dead-letter sender that fails or succeeds, so a record is only committed
 * once Kafka has confirmed its dead-letter copy.
 */
class DeadLetterRecoveryTest {

    private static final IngestionProperties PROPERTIES = new IngestionProperties(
            new IngestionProperties.Topic("incident-events", 3, Duration.ofHours(24)),
            new IngestionProperties.DeadLetter("incident-events.DLT", Duration.ofDays(7)),
            new IngestionProperties.Retry(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 8));

    private static final TopicPartition PARTITION = new TopicPartition("incident-events", 2);
    private static final long OFFSET = 41;

    @SuppressWarnings("unchecked")
    private final KafkaOperations<Object, Object> deadLetterTemplate = mock(KafkaOperations.class);

    @SuppressWarnings("unchecked")
    private final Consumer<Object, Object> consumer = mock(Consumer.class);

    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    private final ConsumerRecord<Object, Object> record =
            new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), OFFSET, "signal-service", "{}");

    // Non-retryable, so the handler goes straight to dead-letter recovery.
    private final Exception invalid = new ListenerExecutionFailedException("listener failed",
            new InvalidEventException("invalid fields: service"));

    private DefaultErrorHandler handler;

    @BeforeEach
    void setUp() {
        ContainerProperties containerProperties = new ContainerProperties(PARTITION.topic());
        containerProperties.setAckMode(AckMode.MANUAL_IMMEDIATE);
        when(container.getContainerProperties()).thenReturn(containerProperties);
        handler = KafkaConsumerConfig.errorHandler(PROPERTIES, deadLetterTemplate);
    }

    @Test
    void failedDeadLetterSendLeavesTheRecordUncommittedAndSeeksBackToIt() {
        when(deadLetterTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")));

        Throwable thrown = catchThrowable(() -> handler.handleRemaining(invalid, List.of(record), consumer, container));

        assertThat(thrown).as("the record is not treated as handled").isNotNull();
        verify(consumer).seek(PARTITION, OFFSET);
        assertThat(commits()).as("no offset is committed").isEmpty();
    }

    @Test
    void confirmedDeadLetterSendCommitsTheNextOffset() {
        when(deadLetterTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        handler.handleRemaining(invalid, List.of(record), consumer, container);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(deadLetterTemplate).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("incident-events.DLT");
        assertThat(sent.getValue().partition()).isEqualTo(PARTITION.partition());
        assertThat(sent.getValue().key()).isEqualTo("signal-service");
        verify(consumer, never()).seek(any(TopicPartition.class), any(Long.class));
        assertThat(commits()).singleElement()
                .isEqualTo(Map.of(PARTITION, new OffsetAndMetadata(OFFSET + 1)));
    }

    /** The offset maps passed to any commitSync overload. */
    private List<Object> commits() {
        return mockingDetails(consumer).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("commitSync"))
                .map(Invocation::getArguments)
                .filter(arguments -> arguments.length > 0)
                .map(arguments -> arguments[0])
                .toList();
    }
}
