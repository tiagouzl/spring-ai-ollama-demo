package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional API-key authentication for the {@code /ai/**} endpoints: when
 * {@code app.auth.api-key} is set, every request must carry a matching
 * {@code X-API-Key} header, otherwise a structured 401 is returned.
 * <p>
 * Empty key (the default) leaves the endpoints open — ideal for local demos.
 * For production, consider a full Spring Security setup (OIDC/JWT) on top.
 * </p>
 */
@Component
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    static final String API_KEY_HEADER = "X-API-Key";

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthInterceptor(@Value("${app.auth.api-key:}") String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflight (OPTIONS) carries no credentials, so it must pass
        // untouched — otherwise a browser's preflight for a protected endpoint
        // would be rejected with 401 before the real request is ever sent.
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        if (apiKey.isEmpty()) {
            return true; // auth not configured — open
        }
        if (constantTimeEquals(apiKey, request.getHeader(API_KEY_HEADER))) {
            return true;
        }
        ApiErrorWriter.write(response, objectMapper, 401, "Unauthorized",
                "Missing or invalid " + API_KEY_HEADER + " header. Set app.auth.api-key to configure access.",
                request);
        return false;
    }

    /**
     * Constant-time comparison of the configured key against the incoming header so
     * a wrong key cannot leak its length/byte content through response-timing
     * differences. {@link MessageDigest#isEqual(byte[], byte[])} keeps the inner
     * loop length-independent; callers must never use {@link String#equals(Object)}
     * for secret comparison.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}