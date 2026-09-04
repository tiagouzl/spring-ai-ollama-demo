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

    @MockBean
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
    }
}