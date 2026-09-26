package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IncidentEventRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    @Autowired
    private IncidentEventRepository repository;

    @Test
    void insertsOnceAndKeepsFirstPayload() {
        Instant timestamp = Instant.parse("2026-09-26T14:30:05.123Z");

        int first = repository.insertIfAbsent("EVT-1", "CBTC", "signal-service", "CRITICAL", "first", "OPEN",
                timestamp);
        int second = repository.insertIfAbsent("EVT-1", "ATS", "route-service", "INFO", "second", "RESOLVED",
                timestamp.plusSeconds(60));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(repository.count()).isEqualTo(1);

        IncidentEvent stored = repository.findByEventId("EVT-1").orElseThrow();
        assertThat(stored.getId()).isNotNull();
        assertThat(stored.getSource()).isEqualTo("CBTC");
        assertThat(stored.getService()).isEqualTo("signal-service");
        assertThat(stored.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(stored.getMessage()).isEqualTo("first");
        assertThat(stored.getStatus()).isEqualTo(EventStatus.OPEN);
        assertThat(stored.getTimestamp()).isEqualTo(timestamp);
        assertThat(stored.getReceivedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
        assertThat(stored.getVersion()).isZero();
    }

    @Test
    void rejectsOverLengthServiceAsDataIntegrityViolation() {
        assertThatThrownBy(() -> repository.insertIfAbsent("EVT-2", "CBTC", "s".repeat(65), "INFO", "m", "OPEN",
                Instant.now())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownSourceAsDataIntegrityViolation() {
        assertThatThrownBy(() -> repository.insertIfAbsent("EVT-3", "XYZ", "signal-service", "INFO", "m", "OPEN",
                Instant.now())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
