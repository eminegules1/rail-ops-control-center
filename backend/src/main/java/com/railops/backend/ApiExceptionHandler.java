package com.railops.backend;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
    private static final URI INVALID_STATUS_TRANSITION = URI.create("/problems/invalid-status-transition");

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

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail invalidStatusTransition(InvalidStatusTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setType(INVALID_STATUS_TRANSITION);
        problem.setTitle("Invalid status transition");
        problem.setProperty("allowedTransitions", e.getAllowedTransitions());
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The event was changed by another request; reload it and try again");
        problem.setTitle("Concurrent update");
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
        return badRequest(ex, "Invalid query", detail, headers, status, request);
    }

    /** Names each rejected body field and its rule. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(ex, "Invalid request", detail, headers, status, request);
    }

    /** Names an enum field's allowed values without echoing the rejected text; other bad bodies get a fixed hint. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = "request body must be JSON like {\"status\":\"ACKNOWLEDGED\"}";
        if (ex.getCause() instanceof MismatchedInputException mismatch && mismatch.getTargetType() != null
                && mismatch.getTargetType().isEnum()) {
            List<JsonMappingException.Reference> path = mismatch.getPath();
            String field = path.isEmpty() ? null : path.get(path.size() - 1).getFieldName();
            if (field != null) {
                detail = field + " must be one of " + enumValues(mismatch.getTargetType());
            }
        }
        return badRequest(ex, "Invalid request", detail, headers, status, request);
    }

    /** States the expected type without echoing the rejected value. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName();
        Class<?> type = ex.getRequiredType();
        String detail;
        if (type != null && type.isEnum()) {
            detail = name + " must be one of " + enumValues(type);
        } else if (type == int.class || type == Integer.class) {
            detail = name + " must be a whole number";
        } else {
            detail = name + " has an invalid value";
        }
        return badRequest(ex, "Invalid query", detail, headers, status, request);
    }

    private static String enumValues(Class<?> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
    }

    private ResponseEntity<Object> badRequest(Exception ex, String title, String detail, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
