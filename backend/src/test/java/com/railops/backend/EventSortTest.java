package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class EventSortTest {

    @Test
    void defaultsToNewestFirst() {
        Sort expected = Sort.by(Sort.Direction.DESC, "timestamp").and(Sort.by(Sort.Direction.DESC, "id"));

        assertThat(EventQueryService.parseSort(null)).isEqualTo(expected);
        assertThat(EventQueryService.parseSort(" ")).isEqualTo(expected);
    }

    @Test
    void fieldAloneSortsAscending() {
        assertThat(EventQueryService.parseSort("service"))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "service").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    @Test
    void acceptsDirectionInAnyCase() {
        assertThat(EventQueryService.parseSort("receivedAt,DESC"))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "receivedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        assertThat(EventQueryService.parseSort("eventId, asc"))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "eventId").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"severity", "status", "id", "message,asc", "timestamp,up", "timestamp,desc,x",
            "timestamp,", ",desc"})
    void rejectsUnsupportedSort(String sort) {
        assertThatThrownBy(() -> EventQueryService.parseSort(sort))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessage("sort must be field or field,asc|desc with field one of eventId, receivedAt, service, "
                        + "source, timestamp");
    }
}
