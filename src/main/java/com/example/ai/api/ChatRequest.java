package com.example.ai.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "message is required and must not be blank")
        @Size(max = 4000, message = "message must be at most 4000 characters") String message) {}
