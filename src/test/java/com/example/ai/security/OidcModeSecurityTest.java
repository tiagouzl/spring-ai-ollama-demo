package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the OIDC mode of /ai/** (spec R1): bearer required with a Bearer
 * challenge, valid tokens pass, and CORS preflight is exempt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo"
})
class OidcModeSecurityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void missingTokenIsRejectedWith401BearerChallenge() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void validBearerTokenIsAccepted() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(bearer(TestTokens.token(Map.of()))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
    }

    private ResponseEntity<String> sessionWith(String token) {
        return rest.exchange("http://localhost:" + port + "/ai/session",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
    }

    @Test
    void corsPreflightIsNotRejectedWith401() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat", HttpMethod.OPTIONS,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectedTokensAnswer401() {
        assertThat(sessionWith(TestTokens.token(Map.of(
                "exp", new java.util.Date(System.currentTimeMillis() - 60_000)))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> r1 = sessionWith(TestTokens.tokenSignedByOtherKey(Map.of()));
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> r2 = sessionWith(TestTokens.token(Map.of(
                "iss", "https://evil.example/realms/test")));
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> r3 = sessionWith(TestTokens.token(Map.of(
                "aud", java.util.List.of("other-api"), "azp", "other-client")));
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> r4 = sessionWith(TestTokens.token(Map.of("sub", "")));
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> r5 = sessionWith(TestTokens.token(Collections.singletonMap("sub", (Object) null)));
        assertThat(r5.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
