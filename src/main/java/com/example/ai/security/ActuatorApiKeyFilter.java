package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extends the optional API-key auth to the sensitive actuator endpoints
 * ({@code /actuator/metrics}, {@code /actuator/prometheus}), which can leak
 * internals such as per-route counters. {@code /actuator/health} and
 * {@code /actuator/info} stay public for load-balancer/k8s probes.
 * <p>
 * A filter (not an MVC interceptor) because actuator endpoints are served by
 * Boot's own handler mapping, which never sees interceptors registered via
 * {@code WebMvcConfigurer} — verified: with only the interceptor, {@code
 * /actuator/metrics} stayed 200 with no key. Filters run before routing, so
 * this covers every handler mapping by construction.
 * </p>
 * <p>
 * In OIDC mode this filter is not registered — the OIDC actuator chain
 * (order 2) protects these endpoints instead (spec R6).
 * </p>
 */
@Component
@ConditionalOnProperty(name = "app.oidc.enabled", havingValue = "false", matchIfMissing = true)
public class ActuatorApiKeyFilter extends OncePerRequestFilter {

    private final String apiKeys;
    private final ObjectMapper objectMapper;

    public ActuatorApiKeyFilter(@Value("${app.auth.api-key:}") String apiKeys, ObjectMapper objectMapper) {
        this.apiKeys = apiKeys == null ? "" : apiKeys;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/actuator/metrics") && !path.startsWith("/actuator/metrics/")
                && !path.equals("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ApiKeyAuthInterceptor.parseKeys(apiKeys).isEmpty() || "OPTIONS".equals(request.getMethod())
                || ApiKeyAuthInterceptor.matchesAny(apiKeys, request.getHeader(ApiKeyAuthInterceptor.API_KEY_HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        ApiErrorWriter.write(response, objectMapper, 401, "Unauthorized",
                "Missing or invalid " + ApiKeyAuthInterceptor.API_KEY_HEADER + " header. Set app.auth.api-key to configure access.",
                request);
    }
}
