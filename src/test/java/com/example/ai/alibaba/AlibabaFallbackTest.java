package com.example.ai.alibaba;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the Alibaba DashScope unavailability handling.
 * <p>
 * When {@code DASHSCOPE_API_KEY} is not set ({@code spring.ai.dashscope.api-key}
 * resolves to an empty string in {@code application.yml}), {@link DashScopeEnabledCondition}
 * does not match and {@code /ai/alibaba/chat} must report the dependency as
 * unavailable (503) — no silent Ollama fallback under 200, so clients and
 * monitoring see the real state. Setup hints go to the server log, not the response.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlibabaFallbackTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void statusReportsNotConfiguredWhenApiKeyIsDummy() {
        String body = rest.getForObject("http://localhost:" + port + "/ai/alibaba/status", String.class);
        assertThat(body).contains("NOT CONFIGURED");
        assertThat(body).doesNotContain("fallback to Ollama");
    }

    @Test
    void chatReturnsServiceUnavailableWhenApiKeyIsDummy() {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/ai/alibaba/chat?message=Hello", String.class);

        // Assert: 503 with a short message — no fallback content, no setup instructions
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("not configured");
        assertThat(response.getBody()).contains("DASHSCOPE_API_KEY");
        assertThat(response.getBody()).doesNotContain("export DASHSCOPE_API_KEY");
    }

    @Test
    void chatReturnsServiceUnavailableWithDefaultMessage() {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/ai/alibaba/chat", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
