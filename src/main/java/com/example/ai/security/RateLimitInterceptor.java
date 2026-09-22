package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
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
 * {@code <= 0} disables limiting. Two backends, selected by
 * {@code app.rate-limit.store}:
 * <ul>
 *   <li>{@code memory} (default) — in-memory buckets, per instance;</li>
 *   <li>{@code redis} — the same bucket in Redis via an atomic Lua script, so
 *       the quota is shared across replicas. Any Redis failure <b>falls back
 *       to the in-memory bucket</b> (fail-open: availability beats strictness,
 *       and the fallback is logged).</li>
 * </ul>
 * </p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    // Drop clients that have not called the API for this long, so the map does
    // not grow without bound (one entry per distinct client that ever called it).
    private static final long IDLE_TIMEOUT_NANOS = 10L * 60 * 1_000_000_000; // 10 minutes

    // Worst-case wait for a token refill is one full window (60s).
    static final String RETRY_AFTER_SECONDS = "60";

    // Atomic refill+consume: returns {allowed (1/0), retry-after seconds}.
    private static final String LUA_CONSUME = """
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(d[1])
            if tokens == nil then tokens = capacity end
            local ts = tonumber(d[2])
            if ts == nil then ts = now end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil((1 - tokens) / refill)
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', KEYS[1], 7200)
            return {allowed, retry}
            """;

    private final int capacity;
    private final double refillPerSecond;
    private final ObjectMapper objectMapper;
    private final Counter rejected;
    private final boolean redis;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> consumeScript;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Immutable token-bucket state for one client. */
    record Bucket(long lastRefillNanos, double tokens) {
    }

    public RateLimitInterceptor(@Value("${app.rate-limit.requests-per-minute:60}") int requestsPerMinute,
                                @Value("${app.rate-limit.store:memory}") String store,
                                ObjectMapper objectMapper, MeterRegistry registry,
                                StringRedisTemplate redisTemplate) {
        this.capacity = Math.max(0, requestsPerMinute);
        this.refillPerSecond = capacity / 60.0;
        this.objectMapper = objectMapper;
        this.rejected = Counter.builder("app.security.ratelimit.rejected")
                .description("Requests rejected by the rate limiter").register(registry);
        this.redis = "redis".equalsIgnoreCase(store);
        this.redisTemplate = redisTemplate;
        this.consumeScript = new DefaultRedisScript<>(LUA_CONSUME, List.class);
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
        if (redis) {
            try {
                Long retryAfter = consumeFromRedis(clientKey);
                if (retryAfter == null) {
                    return true; // token consumed from the shared bucket
                }
                return reject(response, request, clientKey, String.valueOf(retryAfter));
            } catch (Exception e) {
                // Fail-open: a Redis outage must not take the API down — the
                // in-memory bucket below keeps serving with per-instance limits.
                log.warn("Redis rate limit unavailable, falling back to memory: {}", e.getMessage());
            }
        }
        if (consumeInMemory(clientKey)) {
            return true;
        }
        return reject(response, request, clientKey, RETRY_AFTER_SECONDS);
    }

    /**
     * Tries the shared Redis bucket. Returns {@code null} when the request is
     * allowed, otherwise the seconds until the next token refills.
     */
    private Long consumeFromRedis(String clientKey) {
        List<Long> result = redisTemplate.execute(consumeScript, List.of("ratelimit:" + clientKey),
                String.valueOf(capacity), String.valueOf(refillPerSecond),
                String.valueOf(System.currentTimeMillis() / 1000.0));
        if (result == null || result.size() < 2 || result.get(0) == null) {
            throw new IllegalStateException("Empty Redis script result");
        }
        return result.get(0) == 1L ? null : Math.max(1L, result.get(1));
    }

    private boolean reject(HttpServletResponse response, HttpServletRequest request,
                           String clientKey, String retryAfter) throws Exception {
        log.warn("Rate limit exceeded for client {}", clientKey);
        rejected.increment();
        response.setHeader("Retry-After", retryAfter);
        ApiErrorWriter.write(response, objectMapper, 429, "Rate limit exceeded",
                "Too many requests. Limit: " + capacity + " per minute per client.", request);
        return false;
    }

    private boolean consumeInMemory(String clientKey) {
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
        return allowed[0];
    }

    private String clientKey(HttpServletRequest request) {
        String apiKey = request.getHeader(ApiKeyAuthInterceptor.API_KEY_HEADER);
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : request.getRemoteAddr();
    }
}
