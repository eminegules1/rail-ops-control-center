package com.railops.backend;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every API error is a ProblemDetail. Extending ResponseEntityExceptionHandler keeps Spring's own 400s and the
 * catch-all below in one advice, so the most specific handler wins.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail eventNotFound(EventNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Event not found");
        return problem;
    }

    @ExceptionHandler(InvalidQueryException.class)
    ProblemDetail invalidQuery(InvalidQueryException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid query");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected API error", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "The request could not be processed");
        problem.setTitle("Internal error");
        return problem;
    }

    /** Names each rejected parameter and its rule; Spring's default detail is only "Validation failure". */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(MessageSourceResolvable::getDefaultMessage)
                        .map(message -> result.getMethodParameter().getParameterName() + " " + message))
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(ex, detail, headers, status, request);
    }

    /** States the expected type without echoing the rejected value. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName();
        Class<?> type = ex.getRequiredType();
        String detail;
        if (type != null && type.isEnum()) {
            detail = name + " must be one of " + Arrays.stream(type.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
        } else if (type == int.class || type == Integer.class) {
            detail = name + " must be a whole number";
        } else {
            detail = name + " has an invalid value";
        }
        return badRequest(ex, detail, headers, status, request);
    }

    private ResponseEntity<Object> badRequest(Exception ex, String detail, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Invalid query");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
