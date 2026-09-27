package com.railops.backend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard and service status reads over the Redis live state. */
@RestController
class DashboardController {

    private final DashboardQueryService dashboard;

    DashboardController(DashboardQueryService dashboard) {
        this.dashboard = dashboard;
    }

    @Operation(summary = "Dashboard summary",
            description = "Event totals, open, acknowledged and active critical counts, severity distribution and "
                    + "service health. Cached for up to 5 seconds; a status change refreshes it at once.")
    @GetMapping("/api/dashboard/summary")
    DashboardSummary summary() {
        return dashboard.summary();
    }

    @Operation(summary = "Service live state",
            description = "Every known service with its health, last event time, latest severity, and open and "
                    + "active incident counts, sorted by name.")
    @GetMapping("/api/services")
    List<ServiceState> services() {
        return dashboard.services();
    }

    @Operation(summary = "Events per minute by severity",
            description = "One bucket per UTC minute of the event timestamp, oldest first, ending with the current "
                    + "minute. Minutes without events have zero counts.")
    @GetMapping("/api/dashboard/timeline")
    List<TimelineBucket> timeline(@Parameter(description = "Window length in minutes")
                                  @RequestParam(defaultValue = "60")
                                  @Min(value = 1, message = "must be between 1 and 120")
                                  @Max(value = 120, message = "must be between 1 and 120") int minutes) {
        return dashboard.timeline(minutes);
    }

    @Operation(summary = "Most recently processed events",
            description = "Newest first, in the order the backend applied them.")
    @GetMapping("/api/dashboard/recent-events")
    List<EventResponse> recentEvents(@RequestParam(defaultValue = "20")
                                     @Min(value = 1, message = "must be between 1 and 50")
                                     @Max(value = 50, message = "must be between 1 and 50") int limit) {
        return dashboard.recentEvents(limit);
    }
}
