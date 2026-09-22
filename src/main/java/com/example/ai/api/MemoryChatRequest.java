package com.example.ai.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemoryChatRequest(
        @NotBlank(message = "sessionId is required and must not be blank")
        @Size(max = 128, message = "sessionId must be at most 128 characters") String sessionId,
        @NotBlank(message = "message is required and must not be blank")
        @Size(max = 4000, message = "message must be at most 4000 characters") String message) {}
