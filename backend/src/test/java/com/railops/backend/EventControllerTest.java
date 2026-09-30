package com.railops.backend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(EventController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class EventControllerTest {

    private static final EventResponse EVENT = new EventResponse("EVT-1", "CBTC", "signal-service",
            Severity.CRITICAL, "Signal failure", EventStatus.OPEN, Instant.parse("2026-09-26T14:30:05.123Z"),
            Instant.parse("2026-09-26T14:30:05.456Z"), Instant.parse("2026-09-26T14:30:06Z"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventQueryService events;

    @MockitoBean
    private IncidentStatusService statuses;

    @Test
    void listReturnsPageEnvelopeWithDefaults() throws Exception {
        when(events.search(new EventFilter(null, null, null, null, null), 0, 20, null))
                .thenReturn(new EventPageResponse(List.of(EVENT), 0, 20, 1, 1));

        mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"eventId":"EVT-1","source":"CBTC","service":"signal-service",
                        "severity":"CRITICAL","message":"Signal failure","status":"OPEN",
                        "timestamp":"2026-09-26T14:30:05.123Z","receivedAt":"2026-09-26T14:30:05.456Z",
                        "updatedAt":"2026-09-26T14:30:06Z"}],
                        "page":0,"size":20,"totalElements":1,"totalPages":1}
                        """, true));
    }

    @Test
    void listPassesEveryParameterToTheService() throws Exception {
        EventFilter filter = new EventFilter(Severity.MAJOR, EventStatus.ACKNOWLEDGED, "ATS", "route-service",
                "conflict");
        when(events.search(filter, 2, 50, "service,asc")).thenReturn(new EventPageResponse(List.of(), 2, 50, 0, 0));

        mvc.perform(get("/api/events")
                        .param("severity", "MAJOR")
                        .param("status", "ACKNOWLEDGED")
                        .param("source", "ATS")
                        .param("service", "route-service")
                        .param("q", "conflict")
                        .param("page", "2")
                        .param("size", "50")
                        .param("sort", "service,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void detailReturnsTheEvent() throws Exception {
        when(events.get("EVT-1")).thenReturn(EVENT);

        mvc.perform(get("/api/events/EVT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("EVT-1"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-26T14:30:05.123Z"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void unknownEventIsNotFoundProblem() throws Exception {
        when(events.get("EVT-404")).thenThrow(new EventNotFoundException("EVT-404"));

        mvc.perform(get("/api/events/EVT-404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Event not found"))
                .andExpect(jsonPath("$.detail").value("No event with id EVT-404"))
                .andExpect(jsonPath("$.instance").value("/api/events/EVT-404"));
    }

    @Test
    void invalidSortIsBadRequestProblem() throws Exception {
        when(events.search(any(), anyInt(), anyInt(), anyString()))
                .thenThrow(new InvalidQueryException("sort must be field or field,asc|desc"));

        mvc.perform(get("/api/events").param("sort", "severity"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid query"))
                .andExpect(jsonPath("$.detail").value("sort must be field or field,asc|desc"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "severity | URGENT | severity must be one of INFO, WARNING, MAJOR, CRITICAL",
            "status   | CLOSED | status must be one of OPEN, ACKNOWLEDGED, RESOLVED",
            "page     | -1     | page must be 0 or more",
            "page     | xyz    | page must be a whole number",
            "size     | 0      | size must be between 1 and 100",
            "size     | 101    | size must be between 1 and 100",
            "size     | 1.5    | size must be a whole number"})
    void invalidParameterNamesItsRule(String name, String value, String detail) throws Exception {
        mvc.perform(get("/api/events").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid query"))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value("/api/events"));

        verify(events, never()).search(any(), anyInt(), anyInt(), any());
    }

    @ParameterizedTest
    @CsvSource({"severity,URGENT-X9", "page,abc-X9", "size,7.5X9"})
    void rejectedValueIsNotEchoed(String name, String value) throws Exception {
        mvc.perform(get("/api/events").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("X9"))));
    }

    @Test
    void overLongSearchNamesTheLimit() throws Exception {
        mvc.perform(get("/api/events").param("q", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("q must be at most 200 characters"));

        verify(events, never()).search(any(), anyInt(), anyInt(), any());
    }

    @Test
    void severalInvalidParametersAreAllNamed() throws Exception {
        mvc.perform(get("/api/events").param("page", "-1").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("page must be 0 or more")))
                .andExpect(jsonPath("$.detail").value(
                        containsString("size must be between 1 and 100")));
    }

    @Test
    void statusChangeReturnsTheUpdatedEvent() throws Exception {
        EventResponse acknowledged = new EventResponse("EVT-1", "CBTC", "signal-service", Severity.CRITICAL,
                "Signal failure", EventStatus.ACKNOWLEDGED, EVENT.timestamp(), EVENT.receivedAt(),
                Instant.parse("2026-09-26T14:31:00Z"));
        when(statuses.changeStatus("EVT-1", EventStatus.ACKNOWLEDGED)).thenReturn(acknowledged);

        mvc.perform(putStatus("EVT-1", "{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("EVT-1"))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-26T14:31:00Z"))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void sameStatusReturnsTheUnchangedEvent() throws Exception {
        when(statuses.changeStatus("EVT-1", EventStatus.OPEN)).thenReturn(EVENT);

        mvc.perform(putStatus("EVT-1", "{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-26T14:30:06Z"));
    }

    @Test
    void statusChangeOfUnknownEventIsNotFound() throws Exception {
        when(statuses.changeStatus("EVT-404", EventStatus.RESOLVED)).thenThrow(new EventNotFoundException("EVT-404"));

        mvc.perform(putStatus("EVT-404", "{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Event not found"));
    }

    @Test
    void invalidTransitionIsConflictWithAllowedTransitions() throws Exception {
        when(statuses.changeStatus("EVT-1", EventStatus.ACKNOWLEDGED))
                .thenThrow(new InvalidStatusTransitionException(EventStatus.RESOLVED, EventStatus.ACKNOWLEDGED));

        mvc.perform(putStatus("EVT-1", "{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"/problems/invalid-status-transition","title":"Invalid status transition",
                        "status":409,"detail":"Cannot change status from RESOLVED to ACKNOWLEDGED",
                        "instance":"/api/events/EVT-1/status","allowedTransitions":["OPEN"]}
                        """, true));
    }

    @Test
    void overlappingWriteIsConflict() throws Exception {
        when(statuses.changeStatus("EVT-1", EventStatus.RESOLVED))
                .thenThrow(new ObjectOptimisticLockingFailureException(IncidentEvent.class, 1L));

        mvc.perform(putStatus("EVT-1", "{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Concurrent update"))
                .andExpect(jsonPath("$.detail").value(
                        "The event was changed by another request; reload it and try again"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"status\":\"CLOSED-X9\"}      | status must be one of OPEN, ACKNOWLEDGED, RESOLVED",
            "{\"status\":\"acknowledged\"}   | status must be one of OPEN, ACKNOWLEDGED, RESOLVED",
            "{\"status\":1}                  | status must be one of OPEN, ACKNOWLEDGED, RESOLVED",
            "{\"status\":null}               | status is required",
            "{}                              | status is required",
            "{\"status\":\"OPEN\"X9          | request body must be JSON like {\"status\":\"ACKNOWLEDGED\"}",
            "''                              | request body must be JSON like {\"status\":\"ACKNOWLEDGED\"}"})
    void invalidBodyIsBadRequestNamingStatus(String body, String detail) throws Exception {
        mvc.perform(putStatus("EVT-1", body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value("/api/events/EVT-1/status"))
                .andExpect(content().string(not(containsString("X9"))));

        verify(statuses, never()).changeStatus(any(), any());
    }

    @Test
    void unexpectedErrorIsGenericProblem() throws Exception {
        when(events.get("EVT-1")).thenThrow(new IllegalStateException("SELECT * FROM events leaked"));

        mvc.perform(get("/api/events/EVT-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(jsonPath("$.detail").value("The request could not be processed"))
                .andExpect(content().string(not(containsString("leaked"))));
    }

    private static MockHttpServletRequestBuilder putStatus(String eventId, String body) {
        return put("/api/events/{eventId}/status", eventId).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
