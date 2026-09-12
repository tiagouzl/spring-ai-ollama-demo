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
 * Locks the CORS behaviour for the default wildcard origin: a browser preflight
 * is answered, the allow-origin is "*", and — crucially — credentials are NOT
 * exposed (the spec forbids "*" + credentials, so this must stay off).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsConfigTest {

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
        // Credentials must never be enabled for a wildcard origin.
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isNull();
    }
}
