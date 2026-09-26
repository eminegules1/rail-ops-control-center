package com.railops.producer;

/** Kafka did not confirm every message of a batch. */
public class PublishException extends RuntimeException {

    private final int requested;
    private final long confirmed;

    public PublishException(int requested, long confirmed, Throwable cause) {
        super("Kafka confirmed " + confirmed + " of " + requested + " events", cause);
        this.requested = requested;
        this.confirmed = confirmed;
    }

    public int requested() {
        return requested;
    }

    public long confirmed() {
        return confirmed;
    }
}
