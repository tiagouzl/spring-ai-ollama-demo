package com.example.ai.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Optional end-to-end test for the shared semantic-cache backend
 * (Testcontainers): with {@code app.cache.semantic.store=redis} the second
 * identical question is served from Redis — the model is called exactly once.
 *
 * <p>Doubly gated so it never slows down the default build or CI:</p>
 * <ul>
 *   <li>{@code @Testcontainers(disabledWithoutDocker = true)} — skipped when no Docker;</li>
 *   <li>{@code E2E_REDIS=true} environment variable — explicit opt-in (shared
 *       with the Redis rate-limit E2E).</li>
 * </ul>
 *
 * <p>Run with:</p>
 * <pre>E2E_REDIS=true ./mvnw test -Dtest=SemanticCacheRedisE2EIT -DfailIfNoTests=false</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "E2E_REDIS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.cache.semantic.enabled=true", "app.cache.semantic.store=redis"})
class SemanticCacheRedisE2EIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private VectorStore vectorStore;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void secondIdenticalQuestionIsServedFromRedis() {
        float[] ones = new float[8];
        Arrays.fill(ones, 1.0f);
        when(embeddingModel.embed(anyString())).thenReturn(ones);
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("first answer"));

        assertThat(rest.getForObject("/ai/chat?message=hello", String.class)).contains("first answer");

        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("must never appear"));
        assertThat(rest.getForObject("/ai/chat?message=hello", String.class)).contains("first answer");
        verify(ollamaChatModel, times(1)).call(any(Prompt.class));
    }
}
