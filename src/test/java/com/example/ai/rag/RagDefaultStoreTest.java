package com.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.ollama.OllamaChatModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the default store backend: without {@code app.rag.store=pgvector} the
 * app wires the local file-backed {@link SimpleVectorStore} (pgvector stays
 * opt-in for prod). No {@code @MockitoBean VectorStore} here on purpose — the
 * real bean must be visible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagDefaultStoreTest {

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void defaultProfileUsesTheSimpleFileBackedStore() {
        assertThat(vectorStore).isInstanceOf(SimpleVectorStore.class);
    }
}
