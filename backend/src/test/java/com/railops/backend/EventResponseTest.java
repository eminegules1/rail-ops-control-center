package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventResponseTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-09-27T00:26:01.948123Z");

    @Test
    void truncatesAuditTimesToMilliseconds() {
        EventResponse response = EventResponse.from(event(Instant.parse("2026-09-27T00:26:01.965392Z"),
                Instant.parse("2026-09-27T00:30:00.000999Z")));

        assertThat(response.receivedAt()).isEqualTo(Instant.parse("2026-09-27T00:26:01.965Z"));
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-09-27T00:30:00Z"));
    }

    @Test
    void keepsExactMillisecondsAndOtherFieldsUnchanged() {
        Instant exact = Instant.parse("2026-09-27T00:26:01.965Z");

        EventResponse response = EventResponse.from(event(exact, exact));

        assertThat(response).isEqualTo(new EventResponse("EVT-1", "CBTC", "signal-service", Severity.CRITICAL,
                "Signal SG-14 failed to clear", EventStatus.OPEN, TIMESTAMP, exact, exact));
    }

    private static IncidentEvent event(Instant receivedAt, Instant updatedAt) {
        IncidentEvent event = mock(IncidentEvent.class);
        when(event.getEventId()).thenReturn("EVT-1");
        when(event.getSource()).thenReturn("CBTC");
        when(event.getService()).thenReturn("signal-service");
        when(event.getSeverity()).thenReturn(Severity.CRITICAL);
        when(event.getMessage()).thenReturn("Signal SG-14 failed to clear");
        when(event.getStatus()).thenReturn(EventStatus.OPEN);
        when(event.getTimestamp()).thenReturn(TIMESTAMP);
        when(event.getReceivedAt()).thenReturn(receivedAt);
        when(event.getUpdatedAt()).thenReturn(updatedAt);
        return event;
    }
}
