package com.railops.backend;

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

    /** Acks only after the insert commits; any exception goes to the error handler and the offset stays put. */
    @KafkaListener(topics = "${ingestion.topic.name}")
    void onEvent(@Payload(required = false) IncidentEventMessage event, Acknowledgment ack) {
        if (event == null) {
            // A tombstone or empty value; it can never become an event.
            throw new InvalidEventException("empty payload");
        }
        ingestionService.ingest(event);
        ack.acknowledge();
    }
}
