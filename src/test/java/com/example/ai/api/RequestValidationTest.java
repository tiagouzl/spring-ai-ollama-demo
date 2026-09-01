package com.example.ai.api;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the request validation behaviour: blank/missing fields on POST bodies
 * must return 400 with a structured ApiError ("Validation failed") and must
 * never reach the model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestValidationTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    private ResponseEntity<String> post(String url, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url, new HttpEntity<>(json, headers), String.class);
    }

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void blankMessageIsRejectedWith400() {
        ResponseEntity<String> response = post("/ai/chat", "{\"message\":\"   \"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Validation failed");
        assertThat(response.getBody()).contains("message");
    }

    @Test
    void missingMessageIsRejectedWith400() {
        ResponseEntity<String> response = post("/ai/chat", "{}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Validation failed");
    }

    @Test
    void memoryChatWithBlankSessionIdIsRejectedWith400() {
        ResponseEntity<String> response = post("/ai/chat/memory", "{\"sessionId\":\"\",\"message\":\"hi\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("sessionId");
    }

    @Test
    void validMessageStillReachesTheModel() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("valid request accepted"));

        ResponseEntity<String> response = post("/ai/chat", "{\"message\":\"Hello\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("valid request accepted");
    }
}