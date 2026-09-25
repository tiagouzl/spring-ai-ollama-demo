package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R6: with OIDC mode enabled the API-key filter must be inert — a JWT
 * alone reaches metrics, and a key alone does not (modes are exclusive).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo",
        "app.auth.api-key=local-test-key"
})
class OidcActuatorKeyCoexistenceTest {

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtAloneReachesMetrics() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.token(Map.of()));
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void apiKeyAloneDoesNotReachMetrics() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "local-test-key");
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
