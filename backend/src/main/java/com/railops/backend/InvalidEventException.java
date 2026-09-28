package com.railops.backend;

/** A payload that can never be stored; the Kafka error handler sends it to the dead-letter topic without retrying. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
