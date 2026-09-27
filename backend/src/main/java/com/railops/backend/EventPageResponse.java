package com.railops.backend;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable page envelope; Spring Data's own Page JSON is not a guaranteed contract. */
public record EventPageResponse(List<EventResponse> content, int page, int size, long totalElements,
                                int totalPages) {

    static EventPageResponse from(Page<EventResponse> page) {
        return new EventPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
