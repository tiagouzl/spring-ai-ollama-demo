package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-client rate limiter for the {@code /ai/**} endpoints using a
 * <b>token-bucket</b> algorithm: each client holds a bucket pre-filled with
 * {@code requests-per-minute} tokens that refills continuously at that rate
 * (i.e. {@code requests-per-minute} per 60&nbsp;s), with one token consumed per
 * request.
 * <p>
 * Compared to the previous fixed-wall-clock-window limiter, this removes the
 * classic boundary flaw: a client could previously send the full quota just
 * before a window boundary and the full quota again just after — up to
 * {@code 2 x requests-per-minute} in a very short burst. With a token bucket the
 * throughput is smoothed and bounded by the steady refill rate.
 * </p>
 * <p>
 * {@code <= 0} disables limiting. In-memory only — a production deployment
 * behind multiple instances should back this with a shared store
 * (e.g. Redis/Bucket4j) instead, so the buckets are shared across replicas.
 * </p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // Drop clients that have not called the API for this long, so the map does
    // not grow without bound (one entry per distinct client that ever called it).
    private static final long IDLE_TIMEOUT_NANOS = 10L * 60 * 1_000_000_000; // 10 minutes

    private final int capacity;
    private final double refillPerSecond;
    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Immutable token-bucket state for one client. */
    record Bucket(long lastRefillNanos, double tokens) {
    }

    public RateLimitInterceptor(@Value("${app.rate-limit.requests-per-minute:60}") int requestsPerMinute,
                                ObjectMapper objectMapper) {
        this.capacity = Math.max(0, requestsPerMinute);
        this.refillPerSecond = capacity / 60.0;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflight (OPTIONS) performs no real work and carries no client
        // identity — skip it so it neither consumes quota nor gets blocked.
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        if (capacity <= 0) {
            return true; // disabled
        }
        String clientKey = clientKey(request);
        long now = System.nanoTime();

        // Compute the refill + consume atomically per client.
        boolean[] allowed = {false};
        buckets.compute(clientKey, (key, existing) -> {
            Bucket b = existing == null ? new Bucket(now, capacity) : existing;
            double elapsedSec = (now - b.lastRefillNanos()) / 1_000_000_000.0;
            double tokens = Math.min(capacity, b.tokens() + elapsedSec * refillPerSecond);
            if (tokens >= 1.0) {
                allowed[0] = true;
                return new Bucket(now, tokens - 1.0);
            }
            // Refill recorded, but no token available to consume.
            allowed[0] = false;
            return new Bucket(now, tokens);
        });

        // Evict idle clients (no request within IDLE_TIMEOUT) — their bucket is
        // refilled to full by now, so dropping it is lossless on the next request.
        buckets.entrySet().removeIf(e -> (now - e.getValue().lastRefillNanos()) > IDLE_TIMEOUT_NANOS);

        if (!allowed[0]) {
            ApiErrorWriter.write(response, objectMapper, 429, "Rate limit exceeded",
                    "Too many requests. Limit: " + capacity + " per minute per client.", request);
            return false;
        }
        return true;
    }

    private String clientKey(HttpServletRequest request) {
        String apiKey = request.getHeader(ApiKeyAuthInterceptor.API_KEY_HEADER);
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : request.getRemoteAddr();
    }
}
