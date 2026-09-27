package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(EventQueryService.class)
@Testcontainers
class EventQueryServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
    private static final EventFilter NONE = new EventFilter(null, null, null, null, null);

    @Autowired
    private IncidentEventRepository repository;

    @Autowired
    private EventQueryService service;

    @BeforeEach
    void seed() {
        insert("EVT-1", "CBTC", "signal-service", Severity.CRITICAL, "Signal failure at Junction 4", EventStatus.OPEN, 1);
        insert("EVT-2", "ATS", "route-service", Severity.WARNING, "Route conflict detected", EventStatus.ACKNOWLEDGED,
                2);
        insert("EVT-3", "SCADA", "power-service", Severity.CRITICAL, "Traction power 100% lost", EventStatus.RESOLVED,
                3);
        insert("EVT-4", "CBTC", "signal-service", Severity.INFO, "Heartbeat restored", EventStatus.OPEN, 4);
        insert("EVT-5", "PIS", "passenger_info", Severity.MAJOR, "Display board offline", EventStatus.OPEN, 5);
    }

    @Test
    void listsAllNewestFirstByDefault() {
        EventPageResponse page = service.search(NONE, 0, 20, null);

        assertThat(ids(page)).containsExactly("EVT-5", "EVT-4", "EVT-3", "EVT-2", "EVT-1");
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void filtersEachFieldAlone() {
        assertThat(ids(search(new EventFilter(Severity.CRITICAL, null, null, null, null))))
                .containsExactly("EVT-3", "EVT-1");
        assertThat(ids(search(new EventFilter(null, EventStatus.OPEN, null, null, null))))
                .containsExactly("EVT-5", "EVT-4", "EVT-1");
        assertThat(ids(search(new EventFilter(null, null, "CBTC", null, null)))).containsExactly("EVT-4", "EVT-1");
        assertThat(ids(search(new EventFilter(null, null, null, "route-service", null)))).containsExactly("EVT-2");
    }

    @Test
    void combinesFiltersWithAnd() {
        EventFilter filter = new EventFilter(Severity.CRITICAL, EventStatus.OPEN, "CBTC", "signal-service", "junction");

        assertThat(ids(search(filter))).containsExactly("EVT-1");
        assertThat(search(new EventFilter(Severity.INFO, EventStatus.RESOLVED, null, null, null)).content()).isEmpty();
    }

    @Test
    void sourceAndServiceMatchExactly() {
        assertThat(search(new EventFilter(null, null, "cbtc", null, null)).content()).isEmpty();
        assertThat(search(new EventFilter(null, null, null, "signal", null)).content()).isEmpty();
    }

    @Test
    void searchMatchesMessageServiceAndEventIdIgnoringCase() {
        assertThat(ids(search(query("SIGNAL")))).containsExactly("EVT-4", "EVT-1");
        assertThat(ids(search(query("  conflict ")))).containsExactly("EVT-2");
        assertThat(ids(search(query("evt-3")))).containsExactly("EVT-3");
    }

    @Test
    void searchTreatsLikeWildcardsLiterally() {
        assertThat(ids(search(query("100%")))).containsExactly("EVT-3");
        assertThat(ids(search(query("%")))).containsExactly("EVT-3");
        assertThat(ids(search(query("_")))).containsExactly("EVT-5");
        assertThat(search(query("\\")).content()).isEmpty();
    }

    @Test
    void blankSearchDoesNotFilter() {
        assertThat(search(query("   ")).totalElements()).isEqualTo(5);
    }

    @Test
    void sortsByAllowlistedFieldBothWays() {
        assertThat(ids(service.search(NONE, 0, 20, "eventId,asc")))
                .containsExactly("EVT-1", "EVT-2", "EVT-3", "EVT-4", "EVT-5");
        assertThat(ids(service.search(NONE, 0, 20, "service,desc")))
                .containsExactly("EVT-4", "EVT-1", "EVT-2", "EVT-3", "EVT-5");
    }

    @Test
    void paginatesWithTotals() {
        EventPageResponse second = service.search(NONE, 1, 2, "timestamp,asc");

        assertThat(ids(second)).containsExactly("EVT-3", "EVT-4");
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(second.totalPages()).isEqualTo(3);
    }

    @Test
    void pagePastTheEndIsEmpty() {
        EventPageResponse page = service.search(NONE, 9, 20, null);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(5);
    }

    @Test
    void noMatchesIsAnEmptyPage() {
        EventPageResponse page = search(query("no such text"));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void getsDetailByEventId() {
        EventResponse event = service.get("EVT-2");

        assertThat(event.eventId()).isEqualTo("EVT-2");
        assertThat(event.source()).isEqualTo("ATS");
        assertThat(event.service()).isEqualTo("route-service");
        assertThat(event.severity()).isEqualTo(Severity.WARNING);
        assertThat(event.message()).isEqualTo("Route conflict detected");
        assertThat(event.status()).isEqualTo(EventStatus.ACKNOWLEDGED);
        assertThat(event.timestamp()).isEqualTo(T0.plusSeconds(2));
        assertThat(event.receivedAt()).isNotNull();
        assertThat(event.updatedAt()).isNotNull();
    }

    @Test
    void unknownEventIdIsNotFound() {
        assertThatThrownBy(() -> service.get("EVT-404"))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessage("No event with id EVT-404");
    }

    private EventPageResponse search(EventFilter filter) {
        return service.search(filter, 0, 20, null);
    }

    private static EventFilter query(String q) {
        return new EventFilter(null, null, null, null, q);
    }

    private static List<String> ids(EventPageResponse page) {
        return page.content().stream().map(EventResponse::eventId).toList();
    }

    private void insert(String eventId, String source, String service, Severity severity, String message,
                        EventStatus status, int secondsAfterT0) {
        repository.insertIfAbsent(eventId, source, service, severity.name(), message, status.name(),
                T0.plusSeconds(secondsAfterT0));
    }
}
