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

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class EventIngestionServiceTest {

    private final IncidentEventRepository repository = mock(IncidentEventRepository.class);
    private final LiveStateUpdater liveState = mock(LiveStateUpdater.class);
    private final LiveUpdatePublisher liveUpdates = mock(LiveUpdatePublisher.class);
    private ValidatorFactory factory;
    private EventIngestionService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        service = new EventIngestionService(repository, factory.getValidator(), liveState, liveUpdates);
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
    }

    @Test
    void propagatesRedisFailureSoTheRecordIsRetried() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        IncidentEvent stored = storedRow(event.eventId(), event.timestamp());
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(1);
        when(repository.findByEventId(event.eventId())).thenReturn(Optional.of(stored));
        when(liveState.applyEvent(any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.ingest(event)).isInstanceOf(RedisConnectionFailureException.class);
        verifyNoInteractions(liveUpdates);
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
