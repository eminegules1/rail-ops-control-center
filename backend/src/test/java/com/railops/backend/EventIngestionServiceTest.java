package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class EventIngestionServiceTest {

    private final IncidentEventRepository repository = mock(IncidentEventRepository.class);
    private final LiveStateUpdater liveState = mock(LiveStateUpdater.class);
    private final LiveUpdatePublisher liveUpdates = mock(LiveUpdatePublisher.class);
    private final ReconcileState reconcileState = new ReconcileState();
    private final LiveStateLock liveStateLock = new LiveStateLock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ValidatorFactory factory;
    private EventIngestionService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        service = new EventIngestionService(repository, factory.getValidator(), liveState, liveUpdates,
                reconcileState, liveStateLock, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void storesValidEvent() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(1);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));

        assertThat(service.ingest(event)).isEqualTo(IngestionResult.STORED);
        verify(repository).insertIfAbsent(event.eventId(), "CBTC", "signal-service", "CRITICAL",
                event.message(), "OPEN", event.timestamp());
        verify(liveState).applyEvent(event.eventId(), "signal-service", Severity.CRITICAL, EventStatus.OPEN,
                event.timestamp());
        assertThat(meterRegistry.counter("ingestion.events", "outcome", "processed").count()).isEqualTo(1);
    }

    // The stored first copy wins, so Redis gets its values rather than the conflicting duplicate's.
    @Test
    void reportsDuplicateAndAppliesStoredRow() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        Instant storedTimestamp = Instant.parse("2026-09-26T10:00:00Z");
        IncidentEvent stored = mock(IncidentEvent.class);
        when(stored.getEventId()).thenReturn(event.eventId());
        when(stored.getService()).thenReturn("route-service");
        when(stored.getSeverity()).thenReturn(Severity.INFO);
        when(stored.getStatus()).thenReturn(EventStatus.RESOLVED);
        when(stored.getTimestamp()).thenReturn(storedTimestamp);
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(0);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));

        assertThat(service.ingest(event)).isEqualTo(IngestionResult.DUPLICATE);
        verify(liveState).applyEvent(event.eventId(), "route-service", Severity.INFO, EventStatus.RESOLVED,
                storedTimestamp);
        assertThat(meterRegistry.counter("ingestion.events", "outcome", "processed").count()).isEqualTo(1);
    }

    @Test
    void survivesRedisFailureStoresPushesAndMarksReconcileNeeded() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(1);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));
        when(liveState.applyEvent(any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.ingest(event)).isEqualTo(IngestionResult.STORED);
        verify(liveUpdates).eventCreated(EventResponse.from(stored));
        assertThat(reconcileState.isNeeded()).isTrue();
    }

    // The redelivery's own applyEvent call also fails (Redis is still down), so only the Postgres insert result
    // tells it whether this event was already pushed once.
    @Test
    void doesNotPushDuplicateDeliveredDuringTheSameOutage() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(0);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));
        when(liveState.applyEvent(any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.ingest(event)).isEqualTo(IngestionResult.DUPLICATE);
        verifyNoInteractions(liveUpdates);
        assertThat(reconcileState.isNeeded()).isTrue();
    }

    // F-06: pausing the Kafka listener before a rebuild only requests a pause; it does not wait for a record
    // already being processed. This proves the lock is what actually excludes an in-flight ingestion, not the pause.
    @Test
    void ingestWaitsForAConcurrentRebuildToReleaseTheWriteLockThenAppliesAfterIt() throws Exception {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(1);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));
        when(liveState.applyEvent(any(), any(), any(), any(), any())).thenReturn(true);

        CountDownLatch rebuildHoldsTheLock = new CountDownLatch(1);
        CountDownLatch releaseTheRebuild = new CountDownLatch(1);
        Thread rebuild = new Thread(() -> {
            liveStateLock.forRebuild().lock();
            try {
                rebuildHoldsTheLock.countDown();
                releaseTheRebuild.await();
            } catch (InterruptedException ignored) {
                // Test teardown only; the assertions below already ran.
            } finally {
                liveStateLock.forRebuild().unlock();
            }
        });
        rebuild.start();
        assertThat(rebuildHoldsTheLock.await(2, TimeUnit.SECONDS)).isTrue();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<IngestionResult> ingestion = executor.submit(() -> service.ingest(event));

            // While the rebuild holds the write lock, ingestion must not have run yet.
            Thread.sleep(300);
            assertThat(ingestion.isDone()).isFalse();
            verifyNoInteractions(liveUpdates);

            releaseTheRebuild.countDown();
            rebuild.join(2000);

            assertThat(ingestion.get(2, TimeUnit.SECONDS)).isEqualTo(IngestionResult.STORED);
            verify(liveUpdates).eventCreated(EventResponse.from(stored));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidEventWithoutTouchingRepository() {
        IncidentEventMessage invalid = new IncidentEventMessage(" ", "XYZ", "signal-service", Severity.INFO,
                "secret-looking value", EventStatus.OPEN, Instant.now());

        assertThatThrownBy(() -> service.ingest(invalid))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid fields: eventId, source")
                .hasMessageNotContaining("XYZ");
        verifyNoInteractions(repository, liveState, liveUpdates);
        // Rejected before any Postgres/Redis work; EventIngestionService never counts it as processed. The
        // "invalid" outcome is counted downstream, in the Kafka error handler that routes it to the DLT.
        assertThat(meterRegistry.counter("ingestion.events", "outcome", "processed").count()).isZero();
    }

    @Test
    void pushesNewEventOnceItReachesTheLiveState() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(1);
        when(liveState.applyEvent(any(), any(), any(), any(), any())).thenReturn(true);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));

        service.ingest(event);

        verify(liveUpdates).eventCreated(EventResponse.from(stored));
    }

    @Test
    void doesNotPushDuplicateAlreadyInTheLiveState() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(0);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));
        when(liveState.applyEvent(any(), any(), any(), any(), any())).thenReturn(false);

        assertThat(service.ingest(event)).isEqualTo(IngestionResult.DUPLICATE);
        verify(liveUpdates, never()).eventCreated(any());
    }

    // The first delivery stored the row and then failed on Redis: this redelivery is the one that goes live.
    @Test
    void pushesDuplicateWhoseFirstDeliveryMissedTheLiveState() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(0);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));
        when(liveState.applyEvent(any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(service.ingest(event)).isEqualTo(IngestionResult.DUPLICATE);
        verify(liveUpdates).eventCreated(EventResponse.from(stored));
    }

    // The row as the valid message stores it.
    private static IncidentEvent storedRow(String eventId, Instant timestamp) {
        IncidentEvent stored = mock(IncidentEvent.class);
        when(stored.getEventId()).thenReturn(eventId);
        when(stored.getSource()).thenReturn("CBTC");
        when(stored.getService()).thenReturn("signal-service");
        when(stored.getSeverity()).thenReturn(Severity.CRITICAL);
        when(stored.getMessage()).thenReturn("Signal failure");
        when(stored.getStatus()).thenReturn(EventStatus.OPEN);
        when(stored.getTimestamp()).thenReturn(timestamp);
        when(stored.getReceivedAt()).thenReturn(Instant.parse("2026-09-26T10:00:01Z"));
        when(stored.getUpdatedAt()).thenReturn(Instant.parse("2026-09-26T10:00:01Z"));
        return stored;
    }
}
