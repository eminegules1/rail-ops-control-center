package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventIngestionServiceTest {

    private final IncidentEventRepository repository = mock(IncidentEventRepository.class);
    private ValidatorFactory factory;
    private EventIngestionService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        service = new EventIngestionService(repository, factory.getValidator());
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void storesValidEvent() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(1);

        assertThat(service.ingest(event)).isEqualTo(IngestionResult.STORED);
        verify(repository).insertIfAbsent(event.eventId(), "CBTC", "signal-service", "CRITICAL",
                event.message(), "OPEN", event.timestamp());
    }

    @Test
    void reportsDuplicateWhenNothingInserted() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class))).thenReturn(0);

        assertThat(service.ingest(IncidentEventMessageValidationTest.valid())).isEqualTo(IngestionResult.DUPLICATE);
    }

    @Test
    void rejectsInvalidEventWithoutTouchingRepository() {
        IncidentEventMessage invalid = new IncidentEventMessage(" ", "XYZ", "signal-service", Severity.INFO,
                "secret-looking value", EventStatus.OPEN, Instant.now());

        assertThatThrownBy(() -> service.ingest(invalid))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid fields: eventId, source")
                .hasMessageNotContaining("XYZ");
        verifyNoInteractions(repository);
    }
}
