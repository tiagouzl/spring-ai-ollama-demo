package com.example.ai.config;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the CORS behaviour of the default profile: deny by default. With no
 * {@code app.cors.allowed-origins} configured, no CORS headers are sent at
 * all, so browsers are same-origin only — the wildcard stays opt-in (see
 * {@link CorsWildcardConfiguredTest}) and credentialed allowlists are covered
 * by {@link CorsSpecificOriginTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsConfigTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void defaultDeniesCrossOriginPreflight() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://evil.example");
        headers.set("Access-Control-Request-Method", "GET");

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat?message=Hi",
                HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        // No CORS mapping registered -> the response must not grant anything.
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isNull();
    }
}

/**
 * The wildcard contract, preserved for opted-in configurations: a preflight is
 * answered with {@code *} and — crucially — credentials are NOT exposed (the
 * spec forbids "*" + credentials, so this must stay off).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins=*")
class CorsWildcardConfiguredTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void wildcardOriginDoesNotExposeCredentials() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://evil.example");
        headers.set("Access-Control-Request-Method", "GET");

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat?message=Hi",
                HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void deleteMethodIsAllowedInPreflight() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://evil.example");
        headers.set("Access-Control-Request-Method", "DELETE");

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat/memory/some-session",
                HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Methods"))
                .contains("DELETE");
    }
}
