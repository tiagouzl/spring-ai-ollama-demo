package com.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the default store wiring: without {@code app.rag.store=pgvector} the
 * app exposes exactly one {@link VectorStore} bean and it is the local
 * in-memory/file-backed one — pgvector stays opt-in for prod. Asserts the
 * contract (single bean, not pgvector) instead of the concrete class so a
 * swap of the local implementation does not break this test; the behaviour
 * itself is covered by {@code RagEndpointTest}. No {@code @MockitoBean
 * VectorStore} here on purpose — the real bean must be visible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagDefaultStoreTest {

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @Autowired
    private ApplicationContext context;

    @Test
    void defaultProfileWiresExactlyOneLocalVectorStore() {
        Map<String, VectorStore> stores = context.getBeansOfType(VectorStore.class);
        assertThat(stores).hasSize(1);
        assertThat(stores.values().iterator().next()).isNotInstanceOf(PgVectorStore.class);
    }
}
