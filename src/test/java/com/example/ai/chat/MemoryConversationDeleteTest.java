package com.example.ai.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks conversation deletion: DELETE /ai/chat/memory/{sessionId} really
 * wipes the stored history (the next turn's prompt carries no old messages),
 * rejects oversized session ids with 400, and is scoped to the caller's
 * client namespace — another API key cannot delete someone else's memory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.api-key=keyA,keyB")
class MemoryConversationDeleteTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private final List<String> promptsSeen = new ArrayList<>();

    @BeforeEach
    void stubModelAndCapturePrompts() {
        promptsSeen.clear();
        when(ollamaChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            promptsSeen.add(promptText(inv.getArgument(0, Prompt.class)));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("stub reply"))));
        });
    }

    private static String promptText(Prompt prompt) {
        return prompt.getInstructions().stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
    }

    private HttpHeaders headers(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> chat(String apiKey, String sessionId, String message) {
        return rest.exchange("http://localhost:" + port + "/ai/chat/memory",
                HttpMethod.POST,
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}",
                        headers(apiKey)),
                String.class);
    }

    private ResponseEntity<String> delete(String apiKey, String sessionId) {
        return rest.exchange("http://localhost:" + port + "/ai/chat/memory/" + sessionId,
                HttpMethod.DELETE, new HttpEntity<>(headers(apiKey)), String.class);
    }

    @Test
    void deleteWipesHistoryFromTheNextPrompt() {
        String sessionId = UUID.randomUUID().toString();

        assertThat(chat("keyA", sessionId, "first turn").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chat("keyA", sessionId, "second turn").getStatusCode()).isEqualTo(HttpStatus.OK);
        // History is present before deletion: the second prompt sees turn one.
        assertThat(promptsSeen.get(1)).contains("first turn").contains("second turn");

        assertThat(delete("keyA", sessionId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(chat("keyA", sessionId, "third turn").getStatusCode()).isEqualTo(HttpStatus.OK);
        String afterDelete = promptsSeen.get(promptsSeen.size() - 1);
        assertThat(afterDelete).contains("third turn");
        assertThat(afterDelete).doesNotContain("first turn").doesNotContain("second turn");
    }

    @Test
    void deleteIsIdempotentForUnknownSessions() {
        assertThat(delete("keyA", UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void oversizedSessionIdIsRejected() {
        assertThat(delete("keyA", "x".repeat(129)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void otherApiKeyCannotDeleteYourConversation() {
        String sessionId = UUID.randomUUID().toString();
        assertThat(chat("keyA", sessionId, "keep me secret").getStatusCode()).isEqualTo(HttpStatus.OK);

        // keyB derives a different namespace -> clears nothing of keyA's.
        assertThat(delete("keyB", sessionId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(chat("keyA", sessionId, "still there").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promptsSeen.get(promptsSeen.size() - 1)).contains("keep me secret");

        // The owner can delete it.
        assertThat(delete("keyA", sessionId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(chat("keyA", sessionId, "now gone").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promptsSeen.get(promptsSeen.size() - 1)).doesNotContain("keep me secret");
    }
}
