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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the prod wiring of {@code app.post-only-prompts}: with the {@code
 * prod} profile active, prompt-carrying GETs answer 405, prompt-free GETs
 * and POSTs keep working. The prod stack's mandatory env placeholders
 * (DATABASE_URL, REDIS_HOST, APP_API_KEY, CORS_ALLOWED_ORIGINS) are satisfied
 * with test properties and embedded HSQL — the real behaviours under test
 * (post-only flag, auth, CORS pin) come straight from application-prod.yml.
 */
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.api-key=prod-test-key",
        "app.cors.allowed-origins=https://app.example.com",
        "app.rag.store=simple",
        "app.rate-limit.store=memory",
        "spring.data.redis.host=localhost",
        "spring.datasource.url=jdbc:hsqldb:mem:prodpostonly;shutdown=true",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class PostOnlyProdProfileTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @Test
    void promptCarryingGetIsRejectedWith405() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat?message=hello",
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("POST");
    }

    @Test
    void promptFreeGetStillWorks() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session",
                HttpMethod.GET, new HttpEntity<>(apiKeyHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void postStillWorks() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(java.util.List.of(
                        new Generation(new AssistantMessage("stub")))));

        HttpHeaders headers = apiKeyHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat",
                HttpMethod.POST, new HttpEntity<>("{\"message\":\"hello\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders apiKeyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "prod-test-key");
        return headers;
    }
}

/**
 * The prod yml itself must flip the flag — the Spring test above would still
 * pass if application-prod.yml silently dropped the property (the filter's
 * built-in default is {@code false}).
 */
class ProdPostOnlyYmlTest {

    @Test
    void prodProfileEnablesPostOnlyPrompts() throws Exception {
        try (var in = Files.newInputStream(Path.of("src/main/resources/application-prod.yml"))) {
            Map<String, Object> root = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) root.get("app");
            assertThat(app).containsEntry("post-only-prompts", Boolean.TRUE);
        }
    }
}
