package com.example.ai.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional end-to-end test against a REAL Ollama server running in a container
 * (Testcontainers). Pulls a small chat model ({@code qwen2:0.5b}, ~400 MB) inside
 * the container and exercises the full HTTP stack without any mocks.
 *
 * <p>Doubly gated so it never slows down the default build or CI:</p>
 * <ul>
 *   <li>{@code @Testcontainers(disabledWithoutDocker = true)} — skipped when no Docker;</li>
 *   <li>{@code E2E_OLLAMA=true} environment variable — explicit opt-in.</li>
 * </ul>
 *
 * <p>Run with:</p>
 * <pre>E2E_OLLAMA=true ./mvnw test -Dtest=OllamaE2EIT -DfailIfNoTests=false</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "E2E_OLLAMA", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OllamaE2EIT {

    private static final Logger log = LoggerFactory.getLogger(OllamaE2EIT.class);

    private static final String MODEL = "qwen2:0.5b";

    @Container
    static final OllamaContainer OLLAMA =
            new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"));

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        OLLAMA.start();
        OLLAMA.followOutput(new Slf4jLogConsumer(log));
        log.info("Pulling model {} inside Ollama container — first run downloads ~400 MB", MODEL);
        try {
            OLLAMA.execInContainer("ollama", "pull", MODEL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while pulling model " + MODEL, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to pull model " + MODEL, e);
        }
        registry.add("spring.ai.ollama.base-url", OLLAMA::getEndpoint);
        registry.add("spring.ai.ollama.chat.options.model", () -> MODEL);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void realOllamaAnswersThroughTheFullHttpStack() {
        String body = rest.getForObject(
                "http://localhost:" + port + "/ai/chat?message=Reply%20with%20exactly%20the%20word%20PONG",
                String.class);
        assertThat(body).isNotBlank();
    }

    @Test
    void streamingEndpointProducesSseEvents() {
        String body = rest.getForObject(
                "http://localhost:" + port + "/ai/chat/stream?message=Count%20from%201%20to%205",
                String.class);
        assertThat(body).isNotBlank();
        assertThat(body).contains("data:");
    }

    @Test
    void healthEndpointReportsUpWithRealDependencies() {
        String body = rest.getForObject("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(body).contains("UP");
    }
}