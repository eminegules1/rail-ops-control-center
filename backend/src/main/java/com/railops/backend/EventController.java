package com.railops.backend;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
class EventController {

    private final EventQueryService events;

    EventController(EventQueryService events) {
        this.events = events;
    }

    @GetMapping
    EventPageResponse list(@RequestParam(required = false) Severity severity,
                           @RequestParam(required = false) EventStatus status,
                           @RequestParam(required = false) String source,
                           @RequestParam(required = false) String service,
                           @Parameter(description = "Case-insensitive text in message, service or eventId")
                           @RequestParam(required = false)
                           @Size(max = 200, message = "must be at most 200 characters") String q,
                           @Parameter(description = "Zero-based page number")
                           @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be 0 or more") int page,
                           @RequestParam(defaultValue = "20")
                           @Min(value = 1, message = "must be between 1 and 100")
                           @Max(value = 100, message = "must be between 1 and 100") int size,
                           @Parameter(description = "field or field,asc|desc; field is one of timestamp, "
                                   + "receivedAt, service, source, eventId (default timestamp,desc)")
                           @RequestParam(required = false) String sort) {
        return events.search(new EventFilter(severity, status, source, service, q), page, size, sort);
    }

    @GetMapping("/{eventId}")
    EventResponse get(@PathVariable String eventId) {
        return events.get(eventId);
    }
}
