package com.example.ai.security;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Derives stable, non-secret client identifiers for rate-limit buckets,
 * semantic-cache namespaces, and conversation namespaces.
 *
 * <p>The API-key header is never persisted in Redis or written to logs. A valid
 * key is represented by a SHA-256 fingerprint after authentication; anonymous
 * local requests fall back to the remote address.</p>
 */
public final class ClientIdentity {

    static final String ATTRIBUTE = ClientIdentity.class.getName() + ".fingerprint";

    private ClientIdentity() {
    }

    static void authenticate(HttpServletRequest request, String apiKey) {
        request.setAttribute(ATTRIBUTE, fingerprint("key:" + apiKey));
    }

    public static String namespaceFor(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Jwt jwt = jwtAuthentication.getToken();
            String issuer = jwt.getIssuer() == null ? "-" : jwt.getIssuer().toString();
            String azp = jwt.getClaimAsString("azp");
            String azpSegment = (azp == null || azp.isBlank()) ? "-" : azp;
            String sub = jwt.getSubject() == null ? "-" : jwt.getSubject();
            return fingerprint("jwt:" + issuer + ":" + azpSegment + ":" + sub);
        }
        Object authenticated = request.getAttribute(ATTRIBUTE);
        if (authenticated instanceof String fingerprint && !fingerprint.isBlank()) {
            return fingerprint;
        }
        return fingerprint("ip:" + request.getRemoteAddr());
    }

    /**
     * Derives a JDBC-safe 36-character conversation id. The external session id
     * remains an opaque lookup token, while the persisted repository key is
     * scoped to the caller's client namespace.
     */
    public static String conversationId(String clientNamespace, String externalSessionId) {
        byte[] input = (clientNamespace + '\0' + externalSessionId).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(input).toString();
    }

    static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
