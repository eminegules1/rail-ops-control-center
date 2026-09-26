package com.railops.producer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Kafka failures become 503 problems; Spring's own errors use spring.mvc.problemdetails. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PublishException.class)
    ProblemDetail kafkaUnavailable(PublishException e) {
        log.warn("Manual produce failed: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Kafka is unavailable; no confirmation for all events");
        problem.setTitle("Kafka unavailable");
        problem.setProperty("requested", e.requested());
        problem.setProperty("confirmed", e.confirmed());
        return problem;
    }
}
