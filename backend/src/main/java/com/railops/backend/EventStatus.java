package com.railops.backend;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum EventStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED;

    /**
     * Statuses this one may change to, in declaration order. Changing to the same status is a no-op, not a
     * transition.
     */
    public Set<EventStatus> allowedTransitions() {
        return Collections.unmodifiableSet(switch (this) {
            case OPEN -> EnumSet.of(ACKNOWLEDGED, RESOLVED);
            case ACKNOWLEDGED -> EnumSet.of(RESOLVED);
            case RESOLVED -> EnumSet.of(OPEN);
        });
    }

    public boolean canTransitionTo(EventStatus target) {
        return allowedTransitions().contains(target);
    }
}
