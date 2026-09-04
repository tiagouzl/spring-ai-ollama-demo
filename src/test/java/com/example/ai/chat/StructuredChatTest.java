package com.example.ai.chat;

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
 * Locks the structured-output endpoint: the model's JSON reply is parsed into
 * the typed {@code TopicSentiment} record and returned as such (not raw text).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StructuredChatTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void structuredEndpointReturnsParsedTypedRecord() {
        // The model (mocked) replies with a JSON object matching TopicSentiment.
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("{\"topic\":\"Spring AI\",\"sentiment\":\"positive\",\"rating\":9}"));

        ResponseEntity<String> response = rest.getForEntity(
                "/ai/chat/structured?message=Spring%20AI%20is%20great", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"topic\"");
        assertThat(response.getBody()).contains("Spring AI");
        assertThat(response.getBody()).contains("\"sentiment\"");
        assertThat(response.getBody()).contains("positive");
        assertThat(response.getBody()).contains("\"rating\"");
        assertThat(response.getBody()).contains("9");
    }

    @Test
    void structuredPostAcceptsJsonBody() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("{\"topic\":\"Java\",\"sentiment\":\"neutral\",\"rating\":5}"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "/ai/chat/structured",
                new HttpEntity<>("{\"message\":\"Java is a language\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Java");
        assertThat(response.getBody()).contains("neutral");
    }
}