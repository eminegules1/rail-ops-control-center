package com.railops.backend;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, Long>,
        JpaSpecificationExecutor<IncidentEvent> {

    Optional<IncidentEvent> findByEventId(String eventId);

    List<IncidentEvent> findByEventIdIn(Collection<String> eventIds);

    long countByStatus(EventStatus status);

    long countBySeverity(Severity severity);

    long countBySeverityAndStatusNot(Severity severity, EventStatus status);

    /** Newest received row first; used to rebuild the "recent events" list from Postgres when Redis is down. */
    List<IncidentEvent> findByOrderByReceivedAtDesc(Pageable pageable);

    /** The most recent stored row per service (by event {@code timestamp}), for {@code lastEventTime}/{@code latestSeverity}. */
    @Query(value = "SELECT DISTINCT ON (service) * FROM events ORDER BY service, timestamp DESC", nativeQuery = true)
    List<IncidentEvent> findLatestPerService();

    /** Open/active counts per service, grouped the way the Redis service hash tracks them. */
    @Query("""
            SELECT new com.railops.backend.ServiceCounts(e.service,
                SUM(CASE WHEN e.status = com.railops.backend.EventStatus.OPEN THEN 1L ELSE 0L END),
                SUM(CASE WHEN e.status IN (com.railops.backend.EventStatus.OPEN, com.railops.backend.EventStatus.ACKNOWLEDGED) THEN 1L ELSE 0L END),
                SUM(CASE WHEN e.status IN (com.railops.backend.EventStatus.OPEN, com.railops.backend.EventStatus.ACKNOWLEDGED)
                    AND e.severity = com.railops.backend.Severity.CRITICAL THEN 1L ELSE 0L END),
                SUM(CASE WHEN e.status IN (com.railops.backend.EventStatus.OPEN, com.railops.backend.EventStatus.ACKNOWLEDGED)
                    AND e.severity = com.railops.backend.Severity.MAJOR THEN 1L ELSE 0L END),
                SUM(CASE WHEN e.status IN (com.railops.backend.EventStatus.OPEN, com.railops.backend.EventStatus.ACKNOWLEDGED)
                    AND e.severity = com.railops.backend.Severity.WARNING THEN 1L ELSE 0L END))
            FROM IncidentEvent e
            GROUP BY e.service
            """)
    List<ServiceCounts> aggregateServiceCounts();

    /**
     * Per-minute, per-severity counts since {@code start}, for the timeline fallback. Raw rows
     * ({@code bucket, severity, count}); {@code bucket} is a {@code timestamptz}, {@code count} a {@code bigint}.
     * The double {@code AT TIME ZONE 'UTC'} truncates in UTC regardless of the session time zone, then converts the
     * naive result back to a {@code timestamptz} so the driver returns an unambiguous instant.
     */
    @Query(value = """
            SELECT date_trunc('minute', timestamp AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS bucket,
                   severity, COUNT(*) AS event_count
            FROM events
            WHERE timestamp >= :start
            GROUP BY bucket, severity
            """, nativeQuery = true)
    List<Object[]> timelineCounts(@Param("start") Instant start);

    /** Idempotent insert keyed by {@code event_id}; returns 1 when stored, 0 when the event already exists. */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO events (event_id, source, service, severity, message, status, timestamp)
            VALUES (:eventId, :source, :service, :severity, :message, :status, :timestamp)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("source") String source,
                       @Param("service") String service,
                       @Param("severity") String severity,
                       @Param("message") String message,
                       @Param("status") String status,
                       @Param("timestamp") Instant timestamp);
}
