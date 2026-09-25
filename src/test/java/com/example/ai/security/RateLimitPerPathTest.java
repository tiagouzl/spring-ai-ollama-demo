package com.example.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the per-endpoint rate-limit key: exhausting the quota of one endpoint
 * must not block the other endpoints of the same client.
 */
// Distinct properties from RateLimitTest so each class gets its own Spring
// context — the in-memory buckets are mutable state shared per context.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.requests-per-minute=3")
class RateLimitPerPathTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @Test
    void exhaustingOneEndpointDoesNotBlockTheOthers() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("limited reply")))));

        assertThat(rest.getForEntity("/ai/chat?message=one", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/ai/chat?message=two", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/ai/chat?message=three", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/ai/chat?message=four", String.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Different endpoint, same client: has its own bucket, so it still passes.
        ResponseEntity<String> other = rest.getForEntity("/ai/session", String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
