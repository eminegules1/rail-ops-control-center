package com.railops.backend;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    private final ReconcileState reconcileState;
    private final LiveStateLock liveStateLock;

    public IncidentStatusService(IncidentEventRepository repository, TransactionTemplate transaction,
                                 LiveStateUpdater liveState, LiveUpdatePublisher liveUpdates,
                                 ReconcileState reconcileState, LiveStateLock liveStateLock) {
        this.repository = repository;
        this.transaction = transaction;
        this.liveState = liveState;
        this.liveUpdates = liveUpdates;
        this.reconcileState = reconcileState;
        this.liveStateLock = liveStateLock;
    }

    /**
     * Changes the event's status when the lifecycle allows it; the same status returns the event unchanged.
     * A Redis failure after the commit is logged, not thrown: Postgres already holds the change. A real change is
     * pushed to live clients.
     *
     * <p>The Postgres commit and the Redis apply attempt both run under the same read lock a live-state rebuild
     * takes exclusively (write lock). This is what keeps the two from racing: either this call fully finishes
     * (commit, then apply) before a concurrent rebuild's Postgres snapshot is taken, so the snapshot already
     * reflects it and no further Redis apply is pending; or it waits for a concurrent rebuild to finish first, so
     * its own commit — and therefore its Redis apply — lands after the rebuild and is never double counted.
     *
     * @throws EventNotFoundException when no event has this id
     * @throws InvalidStatusTransitionException when the lifecycle does not allow the change
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when another write committed first
     */
    public EventResponse changeStatus(String eventId, EventStatus target) {
        MDC.put("eventId", eventId);
        try {
            EventResponse event;
            boolean changed;
            liveStateLock.forLiveStateWrite().lock();
            try {
                // The template commits before returning, so a version conflict surfaces here, before any Redis work.
                Change change = transaction.execute(tx -> {
                    IncidentEvent found = repository.findByEventId(eventId)
                            .orElseThrow(() -> new EventNotFoundException(eventId));
                    EventStatus from = found.getStatus();
                    if (from == target) {
                        return new Change(EventResponse.from(found), from);
                    }
                    if (!from.canTransitionTo(target)) {
                        throw new InvalidStatusTransitionException(from, target);
                    }
                    found.changeStatus(target, Instant.now());
                    repository.flush();
                    return new Change(EventResponse.from(found), from);
                });

                event = change.event();
                changed = change.from() != event.status();
                if (changed) {
                    try {
                        if (!liveState.applyStatusChange(event.service(), event.severity(), change.from(),
                                event.status())) {
                            log.warn("No live state for service of event {}; status change not applied to Redis",
                                    event.eventId());
                        }
                    } catch (DataAccessException | CallNotPermittedException e) {
                        log.warn("Live state not updated for status change of event {}", event.eventId(), e);
                        reconcileState.markNeeded();
                    }
                }
            } finally {
                liveStateLock.forLiveStateWrite().unlock();
            }
            if (changed) {
                // Pushed even when Redis failed: Postgres holds the change.
                liveUpdates.eventUpdated(event);
            }
            return event;
        } finally {
            MDC.remove("eventId");
        }
    }

    /** The committed event and the status it had before the request. */
    private record Change(EventResponse event, EventStatus from) {
    }
}
