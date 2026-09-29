package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.kafka.support.Acknowledgment;

class IncidentEventListenerTest {

    private final EventIngestionService ingestionService = mock(EventIngestionService.class);
    private final IncidentEventListener listener = new IncidentEventListener(ingestionService);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    @Test
    void putsEventIdInMdcDuringIngestionAndClearsItAfter() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        String[] seenDuringIngest = new String[1];
        doAnswer(invocation -> {
            seenDuringIngest[0] = MDC.get("eventId");
            return null;
        }).when(ingestionService).ingest(event);

        listener.onEvent(event, ack);

        assertThat(seenDuringIngest[0]).isEqualTo(event.eventId());
        assertThat(MDC.get("eventId")).isNull();
        verify(ack).acknowledge();
    }

    @Test
    void clearsEventIdFromMdcEvenWhenIngestionThrows() {
        IncidentEventMessage event = IncidentEventMessageValidationTest.valid();
        doThrow(new IllegalStateException("simulated outage")).when(ingestionService).ingest(event);

        assertThatThrownBy(() -> listener.onEvent(event, ack)).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void rejectsANullPayloadWithoutTouchingMdcOrTheIngestionService() {
        assertThatThrownBy(() -> listener.onEvent(null, ack)).isInstanceOf(InvalidEventException.class);

        assertThat(MDC.get("eventId")).isNull();
        verifyNoInteractions(ingestionService, ack);
    }

    @Test
    void doesNotFailWhenEventIdIsMissingFromThePayload() {
        IncidentEventMessage event = new IncidentEventMessage(null, "CBTC", "signal-service", Severity.INFO, "m",
                EventStatus.OPEN, Instant.now());

        listener.onEvent(event, ack);

        assertThat(MDC.get("eventId")).isNull();
        verify(ack).acknowledge();
    }
}
