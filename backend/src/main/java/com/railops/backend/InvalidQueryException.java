package com.railops.backend;

/** A list query parameter that Bean Validation cannot express, such as an unsupported sort. */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
