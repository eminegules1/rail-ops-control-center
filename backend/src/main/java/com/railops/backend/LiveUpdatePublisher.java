package com.railops.backend;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes event changes to {@code /topic/events} as they happen and the dashboard summary to {@code /topic/summary}
 * at most once a second, only after a change. Pushing is best effort: a failure is logged, never thrown, so it
 * cannot fail ingestion or a status update.
 */
@Component
public class LiveUpdatePublisher {

    static final String EVENTS_TOPIC = "/topic/events";
    static final String SUMMARY_TOPIC = "/topic/summary";

    private static final Logger log = LoggerFactory.getLogger(LiveUpdatePublisher.class);

    private final SimpMessageSendingOperations messaging;
    private final DashboardQueryService dashboard;
    private final AtomicBoolean summaryDirty = new AtomicBoolean();

    public LiveUpdatePublisher(SimpMessageSendingOperations messaging, DashboardQueryService dashboard) {
        this.messaging = messaging;
        this.dashboard = dashboard;
    }

    public void eventCreated(EventResponse event) {
        publish(new EventChange(EventChange.Type.CREATED, event));
    }

    public void eventUpdated(EventResponse event) {
        publish(new EventChange(EventChange.Type.UPDATED, event));
    }

    private void publish(EventChange change) {
        summaryDirty.set(true);
        try {
            messaging.convertAndSend(EVENTS_TOPIC, change);
        } catch (MessagingException e) {
            log.warn("Live update not pushed for event {}", change.event().eventId(), e);
        }
    }

    /**
     * Sends a fresh summary when something changed since the last send. It is built from the counters, not the
     * summary cache, which can lag new events by its TTL. A failed send is dropped; the next change marks it again,
     * so a Redis outage does not log every second.
     */
    @Scheduled(fixedDelay = 1000)
    void publishSummary() {
        if (!summaryDirty.getAndSet(false)) {
            return;
        }
        try {
            messaging.convertAndSend(SUMMARY_TOPIC, dashboard.buildSummary());
        } catch (DataAccessException | MessagingException e) {
            log.warn("Live summary not pushed", e);
        }
    }
}
