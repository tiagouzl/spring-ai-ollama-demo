package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Optional API-key authentication for the {@code /ai/**} endpoints: when
 * {@code app.auth.api-key} is set, every request must carry a matching
 * {@code X-API-Key} header, otherwise a structured 401 is returned.
 * <p>
 * Sensitive actuator endpoints are covered separately by
 * {@link ActuatorApiKeyFilter} (interceptors registered via
 * {@code WebMvcConfigurer} never reach Boot's actuator handler mapping).
 * </p>
 * <p>
 * Empty key (the default) leaves the endpoints open — ideal for local demos.
 * For production, consider a full Spring Security setup (OIDC/JWT) on top.
 * </p>
 */
@Component
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    static final String API_KEY_HEADER = "X-API-Key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthInterceptor.class);

    private final String apiKeys;
    private final List<String> keyList;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthInterceptor(@Value("${app.auth.api-key:}") String apiKeys, ObjectMapper objectMapper) {
        this.apiKeys = apiKeys == null ? "" : apiKeys;
        this.keyList = parseKeys(this.apiKeys);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        // CORS preflight (OPTIONS) carries no credentials, so it must pass
        // untouched — otherwise a browser's preflight for a protected endpoint
        // would be rejected with 401 before the real request is ever sent.
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        if (keyList.isEmpty()) {
            return true; // auth not configured — open
        }
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (matchesAny(keyList, apiKey)) {
            ClientIdentity.authenticate(request, apiKey);
            return true;
        }
        log.warn("API key authentication failed for {}", request.getRemoteAddr());
        ApiErrorWriter.write(response, objectMapper, 401, "Unauthorized",
                "Missing or invalid " + API_KEY_HEADER + " header. Set app.auth.api-key to configure access.",
                request);
        return false;
    }

    /**
     * Splits the configured value (comma-separated, blanks dropped) so key
     * rotation is just "add the new key, migrate clients, remove the old one".
     * Single key without comma keeps working exactly as before.
     */
    static List<String> parseKeys(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** True when the header matches ANY configured key (constant-time each). */
    static boolean matchesAny(List<String> keys, String header) {
        if (header == null) {
            return false;
        }
        for (String key : keys) {
            if (constantTimeEquals(key, header)) {
                return true;
            }
        }
        return false;
    }

    /** Single-key overload for callers holding the raw property value. */
    static boolean matchesAny(String configured, String header) {
        return matchesAny(parseKeys(configured), header);
    }

    /**
     * Constant-time comparison of the configured key against the incoming header so
     * a wrong key cannot leak its length/byte content through response-timing
     * differences. {@link MessageDigest#isEqual(byte[], byte[])} keeps the inner
     * loop length-independent; callers must never use {@link String#equals(Object)}
     * for secret comparison.
     */
    static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}