package com.example.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.Semaphore;

/**
 * Bulkhead for LLM calls: at most {@code maxConcurrent} simultaneous calls
 * reach the delegate model; excess calls fail fast with
 * {@link LlmBulkheadFullException} instead of piling up Tomcat threads for
 * the full HTTP timeout. Streaming acquires the permit on subscription and
 * releases it in {@code doFinally}, so SSE requests never leak permits —
 * including on client disconnect. {@code maxConcurrent <= 0} disables the
 * limit (pass-through).
 */
public class ConcurrentBulkheadChatModel implements ChatModel {

    private final ChatModel delegate;
    private final int maxConcurrent;
    private final Semaphore permits; // null = disabled

    public ConcurrentBulkheadChatModel(ChatModel delegate, int maxConcurrent) {
        this.delegate = delegate;
        this.maxConcurrent = maxConcurrent;
        this.permits = maxConcurrent <= 0 ? null : new Semaphore(maxConcurrent, true);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (permits == null) {
            return delegate.call(prompt);
        }
        if (!permits.tryAcquire()) {
            throw new LlmBulkheadFullException(maxConcurrent);
        }
        try {
            return delegate.call(prompt);
        } finally {
            permits.release();
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (permits == null) {
            return delegate.stream(prompt);
        }
        return Flux.defer(() -> {
            if (!permits.tryAcquire()) {
                return Flux.error(new LlmBulkheadFullException(maxConcurrent));
            }
            return delegate.stream(prompt).doFinally(signal -> permits.release());
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
