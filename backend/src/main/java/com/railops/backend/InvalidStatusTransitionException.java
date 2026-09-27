package com.railops.backend;

import java.util.List;

public class InvalidStatusTransitionException extends RuntimeException {

    private final List<EventStatus> allowedTransitions;

    public InvalidStatusTransitionException(EventStatus from, EventStatus to) {
        super("Cannot change status from " + from + " to " + to);
        this.allowedTransitions = List.copyOf(from.allowedTransitions());
    }

    public List<EventStatus> getAllowedTransitions() {
        return allowedTransitions;
    }
}
