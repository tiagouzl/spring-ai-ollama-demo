package com.example.ai.config;

import com.example.ai.api.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bean Validation failures (@NotBlank on request records) -> 400 with field details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", details);
        return build(HttpStatus.BAD_REQUEST, "Validation failed", details, request);
    }

    /**
     * Client asked for a representation the endpoint cannot produce (e.g. Accept:
     * application/json on the Prometheus scrape endpoint, which is text/plain).
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, WebRequest request) {
        log.warn("Not acceptable: {}", ex.getMessage());
        return build(HttpStatus.NOT_ACCEPTABLE, "Not acceptable", "The requested response format is not available for this endpoint.", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, WebRequest request) {
        // Log the detail server-side; return a generic message so internals do not leak to clients.
        log.warn("IllegalState: {}", ex.getMessage());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Illegal state", "The application reached an illegal state. Check the server logs.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Bad request", "Invalid request. Check the server logs for details.", request);
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiError> handleTimeout(TimeoutException ex, WebRequest request) {
        log.warn("Timeout: {}", ex.getMessage());
        return build(HttpStatus.GATEWAY_TIMEOUT, "Model timeout", "The model took too long to respond. Try again or use a shorter prompt.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, WebRequest request) {
        // Log full stack server-side; never expose raw exception messages to clients.
        log.error("Unhandled error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected internal error occurred. Check the server logs.", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String message, WebRequest request) {
        String path = request.getDescription(false).replace("uri=", "");
        ApiError body = new ApiError(Instant.now(), status.value(), error, message, path);
        return ResponseEntity.status(status).body(body);
    }
}
