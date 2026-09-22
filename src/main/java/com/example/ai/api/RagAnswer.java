package com.example.ai.api;

import java.util.List;

/**
 * Grounded RAG answer: the model's reply plus the {@code source} metadata of
 * the retrieved chunks it was grounded in (empty when no relevant context was
 * found — the answer is then explicitly marked as without retrieval).
 */
public record RagAnswer(String answer, List<String> sources) {}
