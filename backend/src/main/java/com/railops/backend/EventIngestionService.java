package com.railops.backend;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final IncidentEventRepository repository;
    private final Validator validator;
    private final LiveStateUpdater liveState;

    public EventIngestionService(IncidentEventRepository repository, Validator validator, LiveStateUpdater liveState) {
        this.repository = repository;
        this.validator = validator;
        this.liveState = liveState;
    }

    /**
     * Validates and stores the event once, then applies it once to the Redis live state. Safe to repeat: a
     * redelivered or duplicated event is left unchanged. Validation runs before any database work, so an invalid
     * event is rejected even while Postgres is down.
     *
     * @throws InvalidEventException when the payload breaks the event contract
     */
    public IngestionResult ingest(IncidentEventMessage event) {
        Set<ConstraintViolation<IncidentEventMessage>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            // Field paths only: values are untrusted and may be long.
            String fields = violations.stream()
                    .map(v -> v.getPropertyPath().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new InvalidEventException("invalid fields: " + fields);
        }

        int inserted = repository.insertIfAbsent(event.eventId(), event.source(), event.service(),
                event.severity().name(), event.message(), event.status().name(), event.timestamp());
        if (inserted == 0) {
            log.info("Duplicate event {} skipped", event.eventId());
            // Still apply: the first delivery may have stored the row and then failed on Redis. The stored row
            // wins over a conflicting duplicate, and the Redis guard keeps the apply to once.
            IncidentEvent stored = repository.findByEventId(event.eventId())
                    .orElseThrow(() -> new IllegalStateException("Duplicate event row not found"));
            liveState.applyEvent(stored.getEventId(), stored.getService(), stored.getSeverity(), stored.getStatus(),
                    stored.getTimestamp());
            return IngestionResult.DUPLICATE;
        }
        liveState.applyEvent(event.eventId(), event.service(), event.severity(), event.status(), event.timestamp());
        log.debug("Stored event {}", event.eventId());
        return IngestionResult.STORED;
    }
}
