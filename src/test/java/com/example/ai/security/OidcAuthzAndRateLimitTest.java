package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks OIDC-mode authorization behaviour (spec §4/§5/R5): the API-key
 * interceptor accepts an already-JWT-authenticated request, rate-limit
 * buckets are per JWT principal, and one principal cannot delete another's
 * conversation.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo",
        "app.auth.api-key=local-test-key",
        "app.rate-limit.requests-per-minute=3"
})
class OidcAuthzAndRateLimitTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private final List<String> promptsSeen = new ArrayList<>();

    @BeforeEach
    void stubModel() {
        promptsSeen.clear();
        when(ollamaChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            var prompt = inv.getArgument(0, Prompt.class);
            promptsSeen.add(prompt.getInstructions().stream()
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .reduce("", (a, b) -> a + "\n" + b));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("stub reply"))));
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String tokenFor(String sub) {
        return TestTokens.token(Map.of("sub", sub));
    }

    @Test
    void jwtAuthenticatedRequestDoesNotNeedTheApiKey() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(bearer(tokenFor("user-a"))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void apiKeyAloneIsNotEnoughInOidcMode() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "local-test-key");
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rateLimitBucketsArePerJwtPrincipal() {
        String tokenA = tokenFor("user-a");
        for (int i = 0; i < 3; i++) {
            assertThat(rest.exchange("http://localhost:" + port + "/ai/session",
                    HttpMethod.GET, new HttpEntity<>(bearer(tokenA)), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> fourth = rest.exchange("http://localhost:" + port + "/ai/session",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenA)), String.class);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A different principal still has a full bucket.
        assertThat(rest.exchange("http://localhost:" + port + "/ai/session",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenFor("user-b"))), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void principalCannotDeleteAnotherPrincipalsConversation() {
        String sessionId = UUID.randomUUID().toString();
        HttpHeaders chat = bearer(tokenFor("user-a"));
        chat.setContentType(MediaType.APPLICATION_JSON);

        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory", HttpMethod.POST,
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\",\"message\":\"keep me secret\"}", chat),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // user-b deletes: 204 (idempotent) but user-a's history survives.
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory/" + sessionId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(tokenFor("user-b"))), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory", HttpMethod.POST,
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\",\"message\":\"still there\"}", chat),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promptsSeen.get(promptsSeen.size() - 1)).contains("keep me secret");

        // The owner deletes: history is gone from the next prompt.
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory/" + sessionId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(tokenFor("user-a"))), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory", HttpMethod.POST,
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\",\"message\":\"now gone\"}", chat),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promptsSeen.get(promptsSeen.size() - 1)).doesNotContain("keep me secret");
    }
}
