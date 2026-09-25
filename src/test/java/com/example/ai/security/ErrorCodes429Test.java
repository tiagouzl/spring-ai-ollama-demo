package com.example.ai.security;

import com.example.ai.api.ApiError;
import com.example.ai.config.GlobalExceptionHandler;
import com.example.ai.config.LlmBulkheadFullException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R9: the two 429 sources must be distinguishable via ApiError.error —
 * there is no separate `code` field (ApiError.java:5).
 */
class ErrorCodes429Test {

    @Test
    void bulkhead429UsesLlmBulkheadFullError() {
        var handler = new GlobalExceptionHandler();
        var request = new ServletWebRequest(new MockHttpServletRequest("POST", "/ai/chat"));

        ResponseEntity<ApiError> response =
                handler.handleLlmBusy(new LlmBulkheadFullException(4), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("llm_bulkhead_full");
    }

    @Test
    void rateLimit429UsesRateLimitExceededError() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var request = new MockHttpServletRequest("GET", "/ai/chat");
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        ApiErrorWriter.write(response, objectMapper, 429,
                "rate_limit_exceeded", "Too many requests", request);

        assertThat(response.getStatus()).isEqualTo(429);
        ApiError body = objectMapper.readValue(response.getContentAsByteArray(), ApiError.class);
        assertThat(body.error()).isEqualTo("rate_limit_exceeded");
    }
}
