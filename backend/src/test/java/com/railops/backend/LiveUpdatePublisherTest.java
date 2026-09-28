package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

class LiveUpdatePublisherTest {

    private static final EventResponse EVENT = new EventResponse("EVT-1", "CBTC", "signal-service",
            Severity.CRITICAL, "Signal failure", EventStatus.OPEN, Instant.parse("2026-09-26T10:00:00Z"),
            Instant.parse("2026-09-26T10:00:01Z"), Instant.parse("2026-09-26T10:00:01Z"));
    private static final DashboardSummary SUMMARY = new DashboardSummary(1, 1, 0, 1, Map.of(), List.of());

    private final SimpMessageSendingOperations messaging = mock(SimpMessageSendingOperations.class);
    private final DashboardQueryService dashboard = mock(DashboardQueryService.class);
    private final LiveUpdatePublisher publisher = new LiveUpdatePublisher(messaging, dashboard);

    @Test
    void pushesCreatedAndUpdatedEvents() {
        publisher.eventCreated(EVENT);
        publisher.eventUpdated(EVENT);

        verify(messaging).convertAndSend("/topic/events", new EventChange(EventChange.Type.CREATED, EVENT));
        verify(messaging).convertAndSend("/topic/events", new EventChange(EventChange.Type.UPDATED, EVENT));
    }

    @Test
    void failedEventPushIsSwallowed() {
        doThrow(new MessageDeliveryException("broker down"))
                .when(messaging).convertAndSend(anyString(), any(Object.class));

        assertThatNoException().isThrownBy(() -> publisher.eventCreated(EVENT));
    }

    @Test
    void sendsOneSummaryForSeveralChanges() {
        when(dashboard.buildSummary()).thenReturn(SUMMARY);
        publisher.eventCreated(EVENT);
        publisher.eventUpdated(EVENT);

        publisher.publishSummary();
        publisher.publishSummary();

        verify(messaging, times(1)).convertAndSend("/topic/summary", SUMMARY);
    }

    @Test
    void sendsNoSummaryWithoutAChange() {
        publisher.publishSummary();

        verify(dashboard, never()).buildSummary();
        verify(messaging, never()).convertAndSend(eq("/topic/summary"), any(Object.class));
    }

    @Test
    void failedSummaryIsDroppedUntilTheNextChange() {
        when(dashboard.buildSummary()).thenThrow(new RedisConnectionFailureException("down")).thenReturn(SUMMARY);
        publisher.eventCreated(EVENT);

        assertThatNoException().isThrownBy(publisher::publishSummary);
        publisher.publishSummary();
        verify(dashboard, times(1)).buildSummary();

        publisher.eventUpdated(EVENT);
        publisher.publishSummary();
        verify(messaging).convertAndSend("/topic/summary", SUMMARY);
    }
}
