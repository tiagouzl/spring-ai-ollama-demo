package com.example.ai.api;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank(message = "message is required and must not be blank") String message) {}
