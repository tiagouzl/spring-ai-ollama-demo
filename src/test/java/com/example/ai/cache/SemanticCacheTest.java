package com.example.ai.cache;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the semantic cache behaviour with {@code app.cache.semantic.enabled=true}:
 * <ul>
 *   <li>a question semantically similar to a cached one (same embedding family)
 *       is answered from the cache — the model is not called again;</li>
 *   <li>an unrelated question misses the cache and reaches the model.</li>
 * </ul>
 * The embedding mock maps "similar*" messages to an all-ones vector (cosine 1.0
 * between them) and anything else to an orthogonal vector (cosine 0.0).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cache.semantic.enabled=true")
class SemanticCacheTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    @MockBean
    private EmbeddingModel embeddingModel;

    // Replaces the real VectorStore so the RAG startup ingestion never persists a
    // bogus vector store built from the mocked embeddings.
    @MockBean
    private VectorStore vectorStore;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static float[] embeddingFor(String message) {
        float[] vector = new float[8];
        if (message.startsWith("similar")) {
            Arrays.fill(vector, 1.0f);
        } else {
            vector[0] = 1.0f; // orthogonal to the all-ones family
        }
        return vector;
    }

    @Test
    void similarQuestionHitsCacheAndSkipsModel() {
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> embeddingFor(inv.getArgument(0)));
        when(ollamaChatModel.call(any(Prompt.class))).thenReturn(mockedResponse("cached answer"));

        String first = rest.getForObject("/ai/chat?message=similar%20question%20one", String.class);
        assertThat(first).isEqualTo("cached answer");
        verify(ollamaChatModel, times(1)).call(any(Prompt.class));

        // Same embedding family → semantic hit, model must NOT be called again.
        String second = rest.getForObject("/ai/chat?message=similar%20question%20two", String.class);
        assertThat(second).isEqualTo("cached answer");
        verify(ollamaChatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void differentQuestionMissesCacheAndCallsModel() {
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> embeddingFor(inv.getArgument(0)));
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenAnswer(inv -> mockedResponse("reply " + System.nanoTime()));

        rest.getForObject("/ai/chat?message=similar%20question%20one", String.class);
        String other = rest.getForObject("/ai/chat?message=other%20topic", String.class);

        assertThat(other).startsWith("reply ");
        verify(ollamaChatModel, times(2)).call(any(Prompt.class));
    }
}