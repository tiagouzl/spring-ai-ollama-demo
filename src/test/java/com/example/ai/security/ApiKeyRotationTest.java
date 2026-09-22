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
 * Locks key rotation: {@code app.auth.api-key} accepts a comma-separated list,
 * every listed key is accepted (constant-time match-any) on both {@code /ai/**}
 * and the guarded actuator endpoints, and anything else is still 401.
 * Rotation = add new key, migrate clients, remove old key — no restart dance
 * beyond the property change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.api-key=old-key,new-key")
class ApiKeyRotationTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ResponseEntity<String> getWithKey(String path, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("X-API-Key", key);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void bothOldAndNewKeysAreAccepted() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("rotated reply"));

        assertThat(getWithKey("/ai/chat?message=Hello", "old-key").getBody()).contains("rotated reply");
        assertThat(getWithKey("/ai/chat?message=Hello", "new-key").getBody()).contains("rotated reply");
    }

    @Test
    void unknownKeyIsStillRejected() {
        assertThat(getWithKey("/ai/chat?message=Hello", "wrong-key").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getWithKey("/ai/chat?message=Hello", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void guardedActuatorAcceptsAnyListedKey() {
        assertThat(getWithKey("/actuator/metrics", "new-key").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getWithKey("/actuator/metrics", "wrong-key").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
