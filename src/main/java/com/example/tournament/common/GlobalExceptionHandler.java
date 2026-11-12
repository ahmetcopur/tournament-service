package com.example.tournament.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** The one place that turns exceptions into {code, message} bodies. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry meters;

    public GlobalExceptionHandler(MeterRegistry meters) {
        this.meters = meters;
        // Start every code at 0. A counter that first appears at 1 has no earlier sample, so
        // Prometheus' rate() would never show that first error.
        for (ErrorCode code : ErrorCode.values()) {
            counter(code.name());
        }
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex) {
        return respond(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return respond(ErrorCode.VALIDATION_ERROR, message.isEmpty() ? "Invalid request body" : message);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<ApiError> handleUnreadable(Exception ex) {
        return respond(ErrorCode.VALIDATION_ERROR, "Malformed request");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoRoute(NoResourceFoundException ex) {
        return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }

    /**
     * The UNIQUE constraints are the safety net under the service checks. If a race slips
     * past a check, the database still refuses the write and we report the matching code.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex) {
        String constraint = constraintName(ex);
        ErrorCode code = switch (constraint == null ? "" : constraint) {
            case "uq_users_username" -> ErrorCode.USERNAME_TAKEN;
            case "uq_member_tournament_user" -> ErrorCode.ALREADY_JOINED;
            default -> ErrorCode.CONFLICT;
        };
        if (code == ErrorCode.CONFLICT) {
            log.warn("Integrity violation on constraint {}", constraint, ex);
        }
        return respond(code, code.defaultMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleOther(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            String code = "HTTP_" + status.value();
            count(code);
            return ResponseEntity.status(status).body(new ApiError(code, ex.getMessage()));
        }
        log.error("Unhandled error", ex);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private ResponseEntity<ApiError> respond(ErrorCode code, String message) {
        count(code.name());
        return ResponseEntity.status(code.status()).body(ApiError.of(code, message));
    }

    /** api_errors_total{code=...}: HTTP metrics only show 409, this shows which rule said no. */
    private void count(String code) {
        counter(code).increment();
    }

    private Counter counter(String code) {
        return Counter.builder("api.errors")
                .description("Error responses by error code")
                .tag("code", code)
                .register(meters);
    }

    private static String constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
        }
        return null;
    }
}
