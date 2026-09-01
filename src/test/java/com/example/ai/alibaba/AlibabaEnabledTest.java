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
 * Verifies the critical design fix: the application must start even when
 * DASHSCOPE_API_KEY is set (i.e. two ChatModels exist: ollama + dashscope).
 * Before the fix this failed with NoUniqueBeanDefinitionException for
 * ChatClient.Builder. After {@link com.example.ai.config.PrimaryChatClientConfig}
 * the Ollama model is @Primary and the app starts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.dashscope.api-key=sk-test-dummy-key-for-ci")
class AlibabaEnabledTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    // DashScopeChatModel will be created by DashScopeManualConfig (since api-key != dummy)
    // We mock its call as well to avoid needing a real DashScope API
    @MockBean
    private com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel dashScopeChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void applicationStartsWithDashScopeEnabledAndAlibabaChatUsesDashScope() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("ollama fallback"));
        when(dashScopeChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("dashscope mocked qwen response"));
        // DashScopeChatModel also needs to handle the ChatClient prompt path;
        // the Alibaba controller builds a ChatClient from dashScopeChatModel and calls prompt().call()
        // That will delegate to dashScopeChatModel.call(Prompt)
        // So mocking call is sufficient.

        String status = rest.getForObject("http://localhost:" + port + "/ai/alibaba/status", String.class);
        assertThat(status).contains("CONFIGURED");

        String body = rest.getForObject(
                "http://localhost:" + port + "/ai/alibaba/chat?message=Hello", String.class);
        // When DashScope is configured, it should NOT fallback, but return DashScope's mocked response
        assertThat(body).doesNotContain("[Alibaba DashScope not configured]");
        assertThat(body).contains("dashscope mocked qwen response");
    }

    @Test
    void genericChatStillUsesOllamaWhenDashScopeIsEnabled() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("generic ollama response"));
        when(dashScopeChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("should not be used for generic chat"));

        String body = rest.getForObject(
                "http://localhost:" + port + "/ai/chat?message=Hi", String.class);
        assertThat(body).contains("generic ollama response");
        assertThat(body).doesNotContain("should not be used");
    }
}
