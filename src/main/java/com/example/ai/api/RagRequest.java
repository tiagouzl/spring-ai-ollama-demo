package com.example.ai.api;

import jakarta.validation.constraints.NotBlank;

public record RagRequest(@NotBlank(message = "question is required and must not be blank") String question) {}
