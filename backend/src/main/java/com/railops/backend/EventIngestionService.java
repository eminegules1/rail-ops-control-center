package com.railops.backend;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final IncidentEventRepository repository;
    private final Validator validator;
    private final LiveStateUpdater liveState;
    private final LiveUpdatePublisher liveUpdates;
    private final ReconcileState reconcileState;
    private final LiveStateLock liveStateLock;

    public EventIngestionService(IncidentEventRepository repository, Validator validator, LiveStateUpdater liveState,
                                 LiveUpdatePublisher liveUpdates, ReconcileState reconcileState,
                                 LiveStateLock liveStateLock) {
        this.repository = repository;
        this.validator = validator;
        this.liveState = liveState;
        this.liveUpdates = liveUpdates;
        this.reconcileState = reconcileState;
        this.liveStateLock = liveStateLock;
    }

    /**
     * Validates and stores the event once, then applies it once to the Redis live state. Safe to repeat: a
     * redelivered or duplicated event is left unchanged. Validation runs before any database work, so an invalid
     * event is rejected even while Postgres is down. The event is pushed to live clients once: when it first reaches
     * the live state, or, if Redis is unreachable, when this call is the one that newly stored it in Postgres.
     *
     * <p>The Postgres insert and the Redis apply attempt both run under the same read lock a live-state rebuild
     * takes exclusively (write lock), the same guard {@link IncidentStatusService#changeStatus} uses. Pausing the
     * Kafka listener before a rebuild only requests a pause; it does not wait for a record already being processed,
     * so without this lock an in-flight insert could land between the rebuild's Postgres snapshot and its Redis
     * write and be silently erased or double-counted.
     *
     * @throws InvalidEventException when the payload breaks the event contract
     */
    public IngestionResult ingest(IncidentEventMessage event) {
        Set<ConstraintViolation<IncidentEventMessage>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            // Field paths only: values are untrusted and may be long. A field can break several rules; name it once.
            String fields = violations.stream()
                    .map(v -> v.getPropertyPath().toString())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new InvalidEventException("invalid fields: " + fields);
        }

        IncidentEvent stored;
        int inserted;
        boolean applied;
        liveStateLock.forLiveStateWrite().lock();
        try {
            inserted = repository.insertIfAbsent(event.eventId(), event.source(), event.service(),
                    event.severity().name(), event.message(), event.status().name(), event.timestamp());
            if (inserted == 0) {
                // Still apply: the first delivery may have stored the row and then failed on Redis. The stored row
                // wins over a conflicting duplicate, and the Redis guard keeps the apply to once.
                log.info("Duplicate event {} skipped", event.eventId());
            }
            // Read before the Redis apply, so a Postgres failure here is an ordinary retried ingestion failure
            // rather than a lost push. The row also has receivedAt and updatedAt, which the pushed API shape needs.
            stored = repository.findByEventId(event.eventId())
                    .orElseThrow(() -> new IllegalStateException("Stored event row not found"));
            try {
                applied = liveState.applyEvent(stored.getEventId(), stored.getService(), stored.getSeverity(),
                        stored.getStatus(), stored.getTimestamp());
            } catch (DataAccessException | CallNotPermittedException e) {
                // Postgres already has the row; a Redis outage never fails ingestion, only its live counters, which
                // the reconciler rebuilds once Redis returns.
                log.warn("Live state not updated for new event {}", stored.getEventId(), e);
                reconcileState.markNeeded();
                applied = false;
            }
        } finally {
            liveStateLock.forLiveStateWrite().unlock();
        }
        // The apply-once guard could not run, so fall back to the Postgres insert result: a genuinely new row is
        // still pushed, a redelivered duplicate during the same outage is not pushed twice.
        if (applied || inserted == 1) {
            liveUpdates.eventCreated(EventResponse.from(stored));
        }
        if (inserted == 0) {
            return IngestionResult.DUPLICATE;
        }
        log.debug("Stored event {}", event.eventId());
        return IngestionResult.STORED;
    }
}
