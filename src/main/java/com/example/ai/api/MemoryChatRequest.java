package com.example.ai.api;

import jakarta.validation.constraints.NotBlank;

public record MemoryChatRequest(
        @NotBlank(message = "sessionId is required and must not be blank") String sessionId,
        @NotBlank(message = "message is required and must not be blank") String message) {}
