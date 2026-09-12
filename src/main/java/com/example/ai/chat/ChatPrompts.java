package com.example.ai.chat;

/**
 * Shared system prompts for the chat controllers.
 * <p>
 * {@link #DEFAULT} is the system prompt used by the plain chat endpoints
 * (simple, streaming, memory, tools). Endpoints that need a different
 * behaviour declare their own prompt inline (e.g. structured output in
 * {@code StructuredChatController}, grounded answers in {@code RagService}).
 * </p>
 */
final class ChatPrompts {

    private ChatPrompts() {
    }

    /** Neutral, concise assistant used by the generic chat endpoints. */
    static final String DEFAULT = "You are a helpful, concise assistant.";
}
