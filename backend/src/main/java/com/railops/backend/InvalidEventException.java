package com.railops.backend;

/** A payload that can never be stored; the Kafka error handler skips it instead of retrying. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
