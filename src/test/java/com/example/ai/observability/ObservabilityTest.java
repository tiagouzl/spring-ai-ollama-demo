package com.example.ai.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the observability behaviour: actuator health is exposed, and the
 * Prometheus scrape endpoint publishes HTTP server metrics (recorded
 * automatically by Micrometer once a request is served).
 */
/**
 * Locks the observability behaviour: actuator health is exposed, and the
 * Prometheus scrape endpoint publishes HTTP server metrics (recorded
 * automatically by Micrometer once a request is served).
 * <p>
 * {@link AutoConfigureObservability} is required because Spring Boot's test
 * support disables metrics export by default in test contexts.
 * </p>
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void healthEndpointReportsUp() {
        String body = rest.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"status\"");
        assertThat(body).contains("UP");
    }

    @Test
    void prometheusEndpointExposesHttpMetrics() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("metric me"));

        // Serve at least one request so Micrometer records HTTP server metrics
        rest.getForObject("/ai/chat?message=Hello", String.class);

        // The Prometheus endpoint produces text/plain only — request it explicitly.
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN));
        ResponseEntity<String> response = rest.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String scrape = response.getBody();
        assertThat(scrape).isNotNull();
        assertThat(scrape).contains("http_server_requests_seconds_count");
        assertThat(scrape).contains("jvm_memory_used_bytes");
    }
}