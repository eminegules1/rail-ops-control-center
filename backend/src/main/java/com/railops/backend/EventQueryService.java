package com.railops.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the events API; Postgres only. */
@Service
@Transactional(readOnly = true)
public class EventQueryService {

    static final String DEFAULT_SORT = "timestamp,desc";
    // severity and status are stored as text, so sorting them would be alphabetical rather than by rank.
    static final Set<String> SORTABLE_FIELDS = Set.of("timestamp", "receivedAt", "service", "source", "eventId");

    private final IncidentEventRepository repository;

    public EventQueryService(IncidentEventRepository repository) {
        this.repository = repository;
    }

    public EventPageResponse search(EventFilter filter, int page, int size, String sort) {
        PageRequest request = PageRequest.of(page, size, parseSort(sort));
        return EventPageResponse.from(repository.findAll(toSpecification(filter), request).map(EventResponse::from));
    }

    public EventResponse get(String eventId) {
        return repository.findByEventId(eventId)
                .map(EventResponse::from)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /** Parses {@code field} or {@code field,asc|desc}; appends {@code id desc} so pages are stable. */
    static Sort parseSort(String sort) {
        String value = sort == null || sort.isBlank() ? DEFAULT_SORT : sort;
        String[] parts = value.split(",", -1);
        if (parts.length > 2) {
            throw invalidSort();
        }
        String field = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(field)) {
            throw invalidSort();
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw invalidSort();
            };
        }
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private static InvalidQueryException invalidSort() {
        return new InvalidQueryException("sort must be field or field,asc|desc with field one of "
                + String.join(", ", SORTABLE_FIELDS.stream().sorted().toList()));
    }

    private static Specification<IncidentEvent> toSpecification(EventFilter filter) {
        List<Specification<IncidentEvent>> specs = new ArrayList<>();
        if (filter.severity() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("severity"), filter.severity()));
        }
        if (filter.status() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.source() != null && !filter.source().isBlank()) {
            specs.add((root, query, cb) -> cb.equal(root.get("source"), filter.source()));
        }
        if (filter.service() != null && !filter.service().isBlank()) {
            specs.add((root, query, cb) -> cb.equal(root.get("service"), filter.service()));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String pattern = "%" + escapeLike(filter.q().trim().toLowerCase(Locale.ROOT)) + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("message")), pattern, '\\'),
                    cb.like(cb.lower(root.get("service")), pattern, '\\'),
                    cb.like(cb.lower(root.get("eventId")), pattern, '\\')));
        }
        return Specification.allOf(specs);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
