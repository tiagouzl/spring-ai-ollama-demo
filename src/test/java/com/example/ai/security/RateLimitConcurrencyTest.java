package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the thread-safety of the in-memory token bucket: concurrent requests
 * from the same client+endpoint may never exhaust more than the initial
 * capacity plus whatever a legitimate time-based refill explains.
 */
class RateLimitConcurrencyTest {

    private static final int CAPACITY = 60;
    private static final int THREADS = 8;
    private static final int ATTEMPTS_PER_THREAD = 15; // 120 attempts > 60 capacity

    @Test
    void concurrentRequestsNeverExceedTheBucket() throws Exception {
        // JavaTimeModule mirrors Boot's auto-configured mapper (ApiError carries Instant).
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                CAPACITY, "memory", new ObjectMapper().registerModule(new JavaTimeModule()),
                new SimpleMeterRegistry(), null);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejectedAs429 = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        try {
            Future<?>[] futures = new Future<?>[THREADS];
            for (int t = 0; t < THREADS; t++) {
                futures[t] = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < ATTEMPTS_PER_THREAD; i++) {
                        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/chat");
                        request.setRemoteAddr("127.0.0.1");
                        MockHttpServletResponse response = new MockHttpServletResponse();
                        if (interceptor.preHandle(request, response, new Object())) {
                            allowed.incrementAndGet();
                        } else if (response.getStatus() == 429) {
                            rejectedAs429.incrementAndGet();
                        }
                    }
                    return null;
                });
            }

            long startedAt = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;

            int attempts = THREADS * ATTEMPTS_PER_THREAD;
            // Never lose initial tokens: the first compute creates a full bucket.
            assertThat(allowed.get()).isGreaterThanOrEqualTo(CAPACITY);
            // Never exceed capacity plus a time-based refill (1 token/s at 60/min).
            int refillCeiling = (int) Math.ceil(elapsedSeconds) + 1;
            assertThat(allowed.get()).isLessThanOrEqualTo(CAPACITY + refillCeiling);
            // Every rejection must surface as HTTP 429.
            assertThat(rejectedAs429.get()).isEqualTo(attempts - allowed.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
