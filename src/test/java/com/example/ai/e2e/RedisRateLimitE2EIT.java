package com.example.ai.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Optional end-to-end test for the shared rate-limit backend (Testcontainers):
 * with {@code app.rate-limit.store=redis} the quota lives in a real Redis, so
 * the 3rd request in a 2/min bucket is rejected with 429 and a numeric
 * Retry-After from the Lua script (not the memory fallback).
 *
 * <p>Doubly gated so it never slows down the default build or CI:</p>
 * <ul>
 *   <li>{@code @Testcontainers(disabledWithoutDocker = true)} — skipped when no Docker;</li>
 *   <li>{@code E2E_REDIS=true} environment variable — explicit opt-in.</li>
 * </ul>
 *
 * <p>Run with:</p>
 * <pre>E2E_REDIS=true ./mvnw test -Dtest=RedisRateLimitE2EIT -DfailIfNoTests=false</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "E2E_REDIS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-limit.store=redis", "app.rate-limit.requests-per-minute=2"})
class RedisRateLimitE2EIT {

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

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void quotaIsEnforcedFromRedisWithNumericRetryAfter() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("shared reply"));

        assertThat(rest.getForEntity("/ai/chat?message=one", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/ai/chat?message=two", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> third = rest.getForEntity("/ai/chat?message=three", String.class);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Integer.parseInt(third.getHeaders().getFirst("Retry-After"))).isGreaterThanOrEqualTo(1);
    }
}
