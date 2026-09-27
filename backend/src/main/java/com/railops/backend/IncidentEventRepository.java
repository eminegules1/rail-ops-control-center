package com.railops.backend;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, Long>,
        JpaSpecificationExecutor<IncidentEvent> {

    Optional<IncidentEvent> findByEventId(String eventId);

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
