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
 * Locks the CORS behaviour for pinned (non-wildcard) origins: the operator may
 * want credentialed requests, so when concrete origins are configured the
 * allow-credentials header is enabled and the origin is echoed back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins=https://app.example.com")
class CorsSpecificOriginTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void specificOriginAllowsCredentials() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://app.example.com");
        headers.set("Access-Control-Request-Method", "GET");

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat?message=Hi",
                HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("https://app.example.com");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
    }
}
