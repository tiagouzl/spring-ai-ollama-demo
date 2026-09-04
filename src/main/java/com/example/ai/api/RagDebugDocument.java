package com.example.ai.api;

import java.util.Map;

/**
 * Public representation of a retrieved RAG chunk for the {@code /ai/rag/debug}
 * endpoint. Maps the internals of Spring AI's {@code Document} (which can change
 * between library versions) to a stable API shape.
 *
 * @param id       chunk id
 * @param text     chunk content
 * @param score    similarity score (may be {@code null} when the store does not provide one)
 * @param metadata chunk metadata (e.g. source document name)
 */
public record RagDebugDocument(String id, String text, Double score, Map<String, Object> metadata) {}