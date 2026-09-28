package com.railops.backend;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Moves incidents through their lifecycle; Postgres first, then the Redis live state. */
@Service
public class IncidentStatusService {

    private static final Logger log = LoggerFactory.getLogger(IncidentStatusService.class);

    private final IncidentEventRepository repository;
    private final TransactionTemplate transaction;
    private final LiveStateUpdater liveState;
    private final LiveUpdatePublisher liveUpdates;

    public IncidentStatusService(IncidentEventRepository repository, TransactionTemplate transaction,
                                 LiveStateUpdater liveState, LiveUpdatePublisher liveUpdates) {
        this.repository = repository;
        this.transaction = transaction;
        this.liveState = liveState;
        this.liveUpdates = liveUpdates;
    }

    /**
     * Changes the event's status when the lifecycle allows it; the same status returns the event unchanged.
     * A Redis failure after the commit is logged, not thrown: Postgres already holds the change. A real change is
     * pushed to live clients.
     *
     * @throws EventNotFoundException when no event has this id
     * @throws InvalidStatusTransitionException when the lifecycle does not allow the change
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when another write committed first
     */
    public EventResponse changeStatus(String eventId, EventStatus target) {
        // The template commits before returning, so a version conflict surfaces here, before any Redis work.
        Change change = transaction.execute(tx -> {
            IncidentEvent event = repository.findByEventId(eventId)
                    .orElseThrow(() -> new EventNotFoundException(eventId));
            EventStatus from = event.getStatus();
            if (from == target) {
                return new Change(EventResponse.from(event), from);
            }
            if (!from.canTransitionTo(target)) {
                throw new InvalidStatusTransitionException(from, target);
            }
            event.changeStatus(target, Instant.now());
            repository.flush();
            return new Change(EventResponse.from(event), from);
        });

        EventResponse event = change.event();
        if (change.from() != event.status()) {
            try {
                if (!liveState.applyStatusChange(event.service(), event.severity(), change.from(), event.status())) {
                    log.warn("No live state for service of event {}; status change not applied to Redis",
                            event.eventId());
                }
            } catch (DataAccessException e) {
                // Feature 14 sets the reconcile-needed flag here.
                log.warn("Live state not updated for status change of event {}", event.eventId(), e);
            }
            // Pushed even when Redis failed: Postgres holds the change.
            liveUpdates.eventUpdated(event);
        }
        return event;
    }

    /** The committed event and the status it had before the request. */
    private record Change(EventResponse event, EventStatus from) {
    }
}
