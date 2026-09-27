package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventStatusTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN, OPEN, false",
            "OPEN, ACKNOWLEDGED, true",
            "OPEN, RESOLVED, true",
            "ACKNOWLEDGED, OPEN, false",
            "ACKNOWLEDGED, ACKNOWLEDGED, false",
            "ACKNOWLEDGED, RESOLVED, true",
            "RESOLVED, OPEN, true",
            "RESOLVED, ACKNOWLEDGED, false",
            "RESOLVED, RESOLVED, false"})
    void followsTheLifecycle(EventStatus from, EventStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @CsvSource({"OPEN, 'ACKNOWLEDGED,RESOLVED'", "ACKNOWLEDGED, RESOLVED", "RESOLVED, OPEN"})
    void listsAllowedTransitionsInDeclarationOrder(EventStatus from, String expected) {
        assertThat(from.allowedTransitions()).map(Enum::name).containsExactly(expected.split(","));
    }
}
