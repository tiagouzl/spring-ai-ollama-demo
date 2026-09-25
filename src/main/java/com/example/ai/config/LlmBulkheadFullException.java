package com.example.ai.config;

/**
 * Thrown when a call arrives while {@code app.llm.max-concurrent} LLM calls
 * are already in flight — the caller should retry shortly (mapped to HTTP 429
 * by {@link GlobalExceptionHandler}).
 */
public class LlmBulkheadFullException extends RuntimeException {

    public LlmBulkheadFullException(int maxConcurrent) {
        super("LLM bulkhead full: " + maxConcurrent + " concurrent calls already in flight");
    }
}
