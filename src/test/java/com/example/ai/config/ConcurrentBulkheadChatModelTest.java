package com.example.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the bulkhead contract of {@link ConcurrentBulkheadChatModel}: at most
 * {@code maxConcurrent} simultaneous calls reach the delegate, excess calls
 * fail fast with {@link LlmBulkheadFullException}, streaming releases its
 * permit on completion, and {@code <= 0} disables the limit entirely.
 */
class ConcurrentBulkheadChatModelTest {

    private static final Prompt PROMPT = new Prompt("hi");

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
    }

    @Test
    void callBeyondLimitFailsFast() throws Exception {
        ChatModel delegate = mock(ChatModel.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.call(any(Prompt.class))).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return response();
        });
        ConcurrentBulkheadChatModel model = new ConcurrentBulkheadChatModel(delegate, 1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ChatResponse> first = pool.submit(() -> model.call(PROMPT));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatExceptionOfType(LlmBulkheadFullException.class)
                    .isThrownBy(() -> model.call(PROMPT));

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void streamReleasesPermitAfterCompletion() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response()));
        when(delegate.call(any(Prompt.class))).thenReturn(response());
        ConcurrentBulkheadChatModel model = new ConcurrentBulkheadChatModel(delegate, 1);

        model.stream(PROMPT).blockLast(Duration.ofSeconds(5));
        // The permit must be free again — a subsequent call goes through.
        assertThat(model.call(PROMPT)).isNotNull();
    }

    @Test
    void streamWhileFullSignalsError() throws Exception {
        ChatModel delegate = mock(ChatModel.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.call(any(Prompt.class))).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return response();
        });
        ConcurrentBulkheadChatModel model = new ConcurrentBulkheadChatModel(delegate, 1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ChatResponse> holder = pool.submit(() -> model.call(PROMPT));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatExceptionOfType(LlmBulkheadFullException.class)
                    .isThrownBy(() -> model.stream(PROMPT).blockLast(Duration.ofSeconds(5)));

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nonPositiveLimitDisablesTheBulkhead() throws Exception {
        ChatModel delegate = mock(ChatModel.class);
        AtomicInteger inFlight = new AtomicInteger();
        CountDownLatch allEntered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.call(any(Prompt.class))).thenAnswer(inv -> {
            inFlight.incrementAndGet();
            allEntered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return response();
        });
        ConcurrentBulkheadChatModel model = new ConcurrentBulkheadChatModel(delegate, 0);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<ChatResponse>> calls = java.util.stream.IntStream.range(0, 3)
                    .mapToObj(i -> pool.submit(() -> model.call(PROMPT)))
                    .toList();
            // Unlimited: all three reach the delegate at the same time.
            assertThat(allEntered.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<ChatResponse> call : calls) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isNotNull();
            }
            assertThat(inFlight.get()).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }
}
