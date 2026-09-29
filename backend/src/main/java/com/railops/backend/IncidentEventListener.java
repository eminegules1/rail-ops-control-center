package com.railops.backend;

import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
class IncidentEventListener {

    private final EventIngestionService ingestionService;

    IncidentEventListener(EventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    static final String LISTENER_ID = "incident-events";

    /**
     * Acks only after the insert commits; any exception goes to the error handler and the offset stays put.
     *
     * <p>{@code groupId} is pinned to the configured Kafka consumer group: without it, an explicit {@code id} (used
     * here so the reconciler can pause/resume this container by name) replaces the group id Spring Kafka would
     * otherwise take from {@code spring.kafka.consumer.group-id}, silently moving the consumer to a new group.
     */
    @KafkaListener(id = LISTENER_ID, groupId = "${spring.kafka.consumer.group-id}", topics = "${ingestion.topic.name}")
    void onEvent(@Payload(required = false) IncidentEventMessage event, Acknowledgment ack) {
        if (event == null) {
            // A tombstone or empty value; it can never become an event.
            throw new InvalidEventException("empty payload");
        }
        // Missing from the payload only when the message doesn't even have this field; still not our eventId to log.
        String eventId = event.eventId();
        if (eventId != null) {
            MDC.put("eventId", eventId);
        }
        try {
            ingestionService.ingest(event);
            ack.acknowledge();
        } finally {
            if (eventId != null) {
                MDC.remove("eventId");
            }
        }
    }
}
