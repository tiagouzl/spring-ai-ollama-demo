package com.example.ai.alibaba;

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
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression test for the Alibaba DashScope fallback.
 * <p>
 * When {@code DASHSCOPE_API_KEY} is not set (i.e. {@code spring.ai.dashscope.api-key=dummy}
 * as defined in {@code application.yml} and {@link DashScopeEnabledCondition}),
 * {@code /ai/alibaba/chat} must not fail — it must fallback to the Ollama response.
 * This test locks that behaviour: it starts the full Spring context without a real key,
 * mocks the Ollama model to avoid needing a running Ollama server, and asserts the
 * fallback message and the mocked Ollama content.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.dashscope.api-key=dummy")
class AlibabaFallbackTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void statusReportsNotConfiguredWhenApiKeyIsDummy() {
        String body = rest.getForObject("http://localhost:" + port + "/ai/alibaba/status", String.class);
        assertThat(body).contains("NOT CONFIGURED");
        assertThat(body).contains("fallback to Ollama");
    }

    @Test
    void chatFallsBackToOllamaWhenApiKeyIsDummy() {
        // Arrange: Ollama will return a fixed response without needing a real server
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("mocked ollama response"));

        String body = rest.getForObject(
                "http://localhost:" + port + "/ai/alibaba/chat?message=Hello", String.class);

        // Assert: fallback header is present and Ollama mock content is returned
        assertThat(body).contains("[Alibaba DashScope not configured]");
        assertThat(body).contains("DASHSCOPE_API_KEY");
        assertThat(body).contains("mocked ollama response");
    }

    @Test
    void chatFallbackContainsOllamaContentWithDefaultMessage() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("hello from mocked ollama"));

        String body = rest.getForObject(
                "http://localhost:" + port + "/ai/alibaba/chat", String.class);

        assertThat(body).contains("[Alibaba DashScope not configured]");
        assertThat(body).contains("hello from mocked ollama");
    }
}
