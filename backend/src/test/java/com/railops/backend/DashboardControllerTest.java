package com.railops.backend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class DashboardControllerTest {

    private static final Instant TIME = Instant.parse("2026-09-27T12:30:05.123Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardQueryService dashboard;

    @Test
    void summaryHasEveryField() throws Exception {
        when(dashboard.summary()).thenReturn(new DashboardSummary(214, 120, 30, 12, severities(90, 70, 40, 14),
                List.of(new ServiceSummary("signal-service", ServiceHealth.DOWN, TIME))));

        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"totalEvents":214,"openEvents":120,"acknowledgedEvents":30,"criticalEvents":12,
                        "severityDistribution":{"INFO":90,"WARNING":70,"MAJOR":40,"CRITICAL":14},
                        "services":[{"name":"signal-service","status":"DOWN",
                        "lastEventTime":"2026-09-27T12:30:05.123Z"}]}
                        """, true));
    }

    @Test
    void servicesListTheirLiveState() throws Exception {
        when(dashboard.services()).thenReturn(List.of(
                new ServiceState("signal-service", ServiceHealth.DEGRADED, TIME, Severity.MAJOR, 3, 5)));

        mvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"name":"signal-service","status":"DEGRADED","lastEventTime":"2026-09-27T12:30:05.123Z",
                        "latestSeverity":"MAJOR","openCount":3,"activeCount":5}]
                        """, true));
    }

    @Test
    void timelineDefaultsToSixtyMinutes() throws Exception {
        when(dashboard.timeline(60)).thenReturn(List.of(
                new TimelineBucket(Instant.parse("2026-09-27T12:30:00Z"), severities(0, 2, 0, 1))));

        mvc.perform(get("/api/dashboard/timeline"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"minute":"2026-09-27T12:30:00Z","counts":{"INFO":0,"WARNING":2,"MAJOR":0,"CRITICAL":1}}]
                        """, true));
    }

    @ParameterizedTest
    @CsvSource({"1", "120"})
    void timelineAcceptsTheWindowEdges(int minutes) throws Exception {
        when(dashboard.timeline(minutes)).thenReturn(List.of());

        mvc.perform(get("/api/dashboard/timeline").param("minutes", Integer.toString(minutes)))
                .andExpect(status().isOk());
    }

    @Test
    void recentEventsDefaultToTwenty() throws Exception {
        when(dashboard.recentEvents(20)).thenReturn(List.of(new EventResponse("EVT-1", "CBTC", "signal-service",
                Severity.CRITICAL, "Signal failure", EventStatus.OPEN, TIME, TIME, TIME)));

        mvc.perform(get("/api/dashboard/recent-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("EVT-1"))
                .andExpect(jsonPath("$[0].timestamp").value("2026-09-27T12:30:05.123Z"))
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"1", "50"})
    void recentEventsAcceptTheLimitEdges(int limit) throws Exception {
        when(dashboard.recentEvents(limit)).thenReturn(List.of());

        mvc.perform(get("/api/dashboard/recent-events").param("limit", Integer.toString(limit)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/api/dashboard/timeline       | minutes | 0     | minutes must be between 1 and 120",
            "/api/dashboard/timeline       | minutes | 121   | minutes must be between 1 and 120",
            "/api/dashboard/timeline       | minutes | 5X9   | minutes must be a whole number",
            "/api/dashboard/recent-events  | limit   | 0     | limit must be between 1 and 50",
            "/api/dashboard/recent-events  | limit   | 51    | limit must be between 1 and 50",
            "/api/dashboard/recent-events  | limit   | 2.5X9 | limit must be a whole number"})
    void invalidParameterNamesItsRule(String path, String name, String value, String detail) throws Exception {
        mvc.perform(get(path).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid query"))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(path))
                .andExpect(content().string(not(containsString("X9"))));

        verify(dashboard, never()).timeline(anyInt());
        verify(dashboard, never()).recentEvents(anyInt());
    }

    @Test
    void redisFailureIsGenericProblem() throws Exception {
        when(dashboard.summary()).thenThrow(new RedisConnectionFailureException("Unable to connect to redis:6379"));

        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request could not be processed"))
                .andExpect(content().string(not(containsString("6379"))));
    }

    private static Map<Severity, Long> severities(long info, long warning, long major, long critical) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        counts.put(Severity.INFO, info);
        counts.put(Severity.WARNING, warning);
        counts.put(Severity.MAJOR, major);
        counts.put(Severity.CRITICAL, critical);
        return counts;
    }
}
