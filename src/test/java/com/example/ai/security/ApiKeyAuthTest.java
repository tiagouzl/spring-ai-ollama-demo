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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the optional API-key auth: with {@code app.auth.api-key} set, requests
 * without the correct {@code X-API-Key} header are rejected with a structured
 * 401 and never reach the model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.api-key=test-secret-key")
class ApiKeyAuthTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ResponseEntity<String> getWithKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", key);
        return rest.exchange("/ai/chat?message=Hello", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void requestWithoutApiKeyIsRejectedWith401() {
        ResponseEntity<String> response = rest.getForEntity("/ai/chat?message=Hello", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Unauthorized");
        assertThat(response.getBody()).contains("X-API-Key");
    }

    @Test
    void requestWithWrongApiKeyIsRejectedWith401() {
        ResponseEntity<String> response = getWithKey("wrong-key");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestWithValidApiKeyReachesTheModel() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("authenticated reply"));

        ResponseEntity<String> response = getWithKey("test-secret-key");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("authenticated reply");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void optionsPreflightIsNotBlockedByAuth() {
        // A browser sends an OPTIONS preflight without the X-API-Key header.
        // With auth enabled, it must still pass (so the real request can follow)
        // instead of being rejected with 401.
        ResponseEntity<String> response = rest.exchange(
                "/ai/chat?message=Hello", HttpMethod.OPTIONS,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sensitiveActuatorEndpointsRequireApiKeyButHealthStaysPublic() {
        // /actuator/metrics and /actuator/prometheus can leak internals, so the
        // ActuatorApiKeyFilter puts them behind the same API key; /actuator/health
        // stays open for load-balancer/k8s probes.
        assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void sensitiveActuatorEndpointsAcceptValidApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "test-secret-key");
        assertThat(rest.exchange("/actuator/metrics", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}