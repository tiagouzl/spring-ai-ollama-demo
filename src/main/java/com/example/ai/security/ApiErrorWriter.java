package com.example.ai.security;

import com.example.ai.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;

/**
 * Writes a structured {@link ApiError} JSON response from interceptors/filters
 * that short-circuit before the controller layer (so {@code GlobalExceptionHandler}
 * never sees them).
 */
final class ApiErrorWriter {

    private ApiErrorWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper mapper,
                      int status, String error, String message,
                      HttpServletRequest request) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = new ApiError(Instant.now(), status, error, message, request.getRequestURI());
        mapper.writeValue(response.getOutputStream(), body);
    }
}