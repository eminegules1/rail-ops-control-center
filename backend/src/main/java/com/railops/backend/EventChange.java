package com.railops.backend;

/** A {@code /topic/events} message: an event that was just ingested or whose status just changed. */
public record EventChange(Type type, EventResponse event) {

    public enum Type {
        CREATED,
        UPDATED
    }
}
