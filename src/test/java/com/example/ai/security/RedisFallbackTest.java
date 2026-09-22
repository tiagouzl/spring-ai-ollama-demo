package com.example.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the fail-open contract: with {@code app.rate-limit.store=redis} but no
 * reachable server, requests are still served (in-memory fallback) instead of
 * failing. Redis is pointed at a closed port so connections refuse fast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-limit.store=redis", "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=9", "spring.data.redis.timeout=2s"})
class RedisFallbackTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void unreachableRedisFallsBackToMemoryAndServes() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("fallback reply"));

        assertThat(rest.getForEntity("/ai/chat?message=one", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/ai/chat?message=two", String.class).getBody())
                .contains("fallback reply");
    }
}
