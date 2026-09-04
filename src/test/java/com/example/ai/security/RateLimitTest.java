package com.example.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the rate limiter: with {@code app.rate-limit.requests-per-minute} set,
 * requests beyond the limit within the same window are rejected with a
 * structured 429. Auth is left open here (no api-key) so the limit is keyed by
 * the client IP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.requests-per-minute=2")
class RateLimitTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void thirdRequestWithinWindowIsRejectedWith429() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("limited reply"));

        assertThat(rest.getForEntity("/ai/chat?message=one", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/ai/chat?message=two", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> third = rest.getForEntity("/ai/chat?message=three", String.class);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody()).contains("Too many requests");
    }
}