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
 * Simple fixed-window rate limiter for the {@code /ai/**} endpoints: at most
 * {@code app.rate-limit.requests-per-minute} requests per client (identified by
 * the {@code X-API-Key} header when present, otherwise by IP address). Exceeding
 * the limit returns a structured 429.
 * <p>
 * {@code <= 0} disables limiting. In-memory only — a production deployment behind
 * multiple instances should use a shared store (Redis/Bucket4j) instead.
 * </p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MILLIS = 60_000;

    private final int requestsPerMinute;
    private final ObjectMapper objectMapper;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    record Window(long windowStartMillis, int count) {
    }

    public RateLimitInterceptor(@Value("${app.rate-limit.requests-per-minute:60}") int requestsPerMinute,
                                ObjectMapper objectMapper) {
        this.requestsPerMinute = requestsPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (requestsPerMinute <= 0) {
            return true; // disabled
        }
        String clientKey = clientKey(request);
        long now = System.currentTimeMillis();
        long windowStart = now - (now % WINDOW_MILLIS);

        Window window = windows.compute(clientKey, (key, existing) -> {
            if (existing == null || existing.windowStartMillis() != windowStart) {
                return new Window(windowStart, 1);
            }
            return new Window(windowStart, existing.count() + 1);
        });

        if (window.count() > requestsPerMinute) {
            ApiErrorWriter.write(response, objectMapper, 429, "Rate limit exceeded",
                    "Too many requests. Limit: " + requestsPerMinute + " per minute per client.", request);
            return false;
        }
        return true;
    }

    private String clientKey(HttpServletRequest request) {
        String apiKey = request.getHeader(ApiKeyAuthInterceptor.API_KEY_HEADER);
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : request.getRemoteAddr();
    }
}