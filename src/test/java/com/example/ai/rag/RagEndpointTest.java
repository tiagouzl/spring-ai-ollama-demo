package com.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the RAG endpoint behaviour:
 * <ul>
 *   <li>{@code /ai/rag/debug} returns stable {@code RagDebugDocument} DTOs, not the
 *       internal Spring AI {@code Document} class;</li>
 *   <li>{@code /ai/rag} with no relevant context (empty store or below the similarity
 *       threshold) answers with a note instead of failing;</li>
 *   <li>{@code /ai/rag} failures return a sanitized 503 — exception details never
 *       leak to the client.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagEndpointTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    @MockBean
    private VectorStore vectorStore;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void debugReturnsStableDtoNotInternalDocument() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("chunk text about RAG", Map.of("source", "rag-pattern"))));

        String body = rest.getForObject("/ai/rag/debug?question=What%20is%20RAG%3F", String.class);

        assertThat(body).contains("\"text\"");
        assertThat(body).contains("chunk text about RAG");
        assertThat(body).contains("\"score\"");
        assertThat(body).contains("\"metadata\"");
        assertThat(body).contains("rag-pattern");
        // The internal Spring AI Document class must never appear in the API response.
        assertThat(body).doesNotContain("org.springframework.ai.document.Document");
    }

    @Test
    void ragWithoutRelevantContextAnswersWithNote() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("generic answer"));

        String body = rest.getForObject("/ai/rag?question=something%20unrelated", String.class);

        assertThat(body).contains("generic answer");
        assertThat(body).contains("[Note: no relevant context found");
    }

    @Test
    void ragFailureReturnsSanitized503WithoutLeakingInternals() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("secret internal path /etc/passwd"));

        ResponseEntity<String> response = rest.getForEntity("/ai/rag?question=trigger%20error", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).doesNotContain("secret internal path");
        assertThat(response.getBody()).doesNotContain("RuntimeException");
        assertThat(response.getBody()).contains("ollama pull nomic-embed-text");
    }
}