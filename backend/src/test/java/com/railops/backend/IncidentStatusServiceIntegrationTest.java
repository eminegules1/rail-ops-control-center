package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Not transactional: the service must commit its own transaction, as it does in the running app.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(IncidentStatusService.class)
@Testcontainers
class IncidentStatusServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    private static final String ID = "EVT-1";

    @Autowired
    private IncidentEventRepository repository;

    @Autowired
    private IncidentStatusService service;

    @MockitoBean
    private LiveStateUpdater liveState;

    @MockitoBean
    private LiveUpdatePublisher liveUpdates;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    @ParameterizedTest
    @CsvSource({"OPEN, ACKNOWLEDGED", "OPEN, RESOLVED", "ACKNOWLEDGED, RESOLVED", "RESOLVED, OPEN"})
    void allowedTransitionIsStored(EventStatus from, EventStatus to) {
        insert(from);
        IncidentEvent before = stored();
        // A JVM-time window: the inserted row's updated_at comes from the database clock, which can differ slightly.
        Instant calledAt = Instant.now();

        EventResponse response = service.changeStatus(ID, to);

        Instant returnedAt = Instant.now();
        IncidentEvent after = stored();
        assertThat(response.status()).isEqualTo(to);
        assertThat(after.getStatus()).isEqualTo(to);
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(after.getUpdatedAt()).isBetween(calledAt.truncatedTo(ChronoUnit.MICROS), returnedAt);
        verify(liveState).applyStatusChange("signal-service", Severity.CRITICAL, from, to);
        verify(liveUpdates).eventUpdated(response);
        assertThat(response.updatedAt()).isEqualTo(after.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void sameStatusChangesNothing() {
        insert(EventStatus.ACKNOWLEDGED);
        IncidentEvent before = stored();

        EventResponse response = service.changeStatus(ID, EventStatus.ACKNOWLEDGED);

        IncidentEvent after = stored();
        assertThat(response.status()).isEqualTo(EventStatus.ACKNOWLEDGED);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        verify(liveState, never()).applyStatusChange(any(), any(), any(), any());
        verifyNoInteractions(liveUpdates);
    }

    @Test
    void redisFailureAfterCommitStillReturnsTheChange() {
        insert(EventStatus.OPEN);
        when(liveState.applyStatusChange(any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis is down"));

        EventResponse response = service.changeStatus(ID, EventStatus.RESOLVED);

        assertThat(response.status()).isEqualTo(EventStatus.RESOLVED);
        assertThat(stored().getStatus()).isEqualTo(EventStatus.RESOLVED);
        verify(liveUpdates).eventUpdated(response);
    }

    @Test
    void disallowedTransitionIsRejectedAndNothingChanges() {
        insert(EventStatus.RESOLVED);
        IncidentEvent before = stored();

        assertThatThrownBy(() -> service.changeStatus(ID, EventStatus.ACKNOWLEDGED))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessage("Cannot change status from RESOLVED to ACKNOWLEDGED")
                .extracting(e -> ((InvalidStatusTransitionException) e).getAllowedTransitions())
                .asList().containsExactly(EventStatus.OPEN);

        IncidentEvent after = stored();
        assertThat(after.getStatus()).isEqualTo(EventStatus.RESOLVED);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        verify(liveState, never()).applyStatusChange(any(), any(), any(), any());
        verifyNoInteractions(liveUpdates);
    }

    @Test
    void unknownEventIsNotFound() {
        assertThatThrownBy(() -> service.changeStatus("EVT-404", EventStatus.RESOLVED))
                .isInstanceOf(EventNotFoundException.class);
        verifyNoInteractions(liveUpdates);
    }

    @Test
    void overlappingWriteIsDetectedByVersion() {
        insert(EventStatus.OPEN);
        IncidentEvent first = stored();
        IncidentEvent second = stored();

        first.changeStatus(EventStatus.ACKNOWLEDGED, Instant.now());
        repository.saveAndFlush(first);
        second.changeStatus(EventStatus.RESOLVED, Instant.now());

        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(stored().getStatus()).isEqualTo(EventStatus.ACKNOWLEDGED);
    }

    private void insert(EventStatus status) {
        repository.insertIfAbsent(ID, "CBTC", "signal-service", "CRITICAL", "Signal failure", status.name(),
                Instant.parse("2026-09-26T10:00:00Z"));
    }

    private IncidentEvent stored() {
        return repository.findByEventId(ID).orElseThrow();
    }
}
