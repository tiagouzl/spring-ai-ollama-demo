package com.example.ai.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional end-to-end test for the external vector store (Testcontainers):
 * boots the app with {@code app.rag.store=pgvector} against a real
 * pgvector/pgvector container and verifies the wiring — the store bean type,
 * the auto-created vector table, and the JDBC chat-memory table.
 * <p>
 * No Ollama needed: document ingestion fails safe (embedding unavailable) and
 * is skipped with a warning, exactly like the default profile behaves on CI.
 *
 * <p>Doubly gated so it never slows down the default build or CI:</p>
 * <ul>
 *   <li>{@code @Testcontainers(disabledWithoutDocker = true)} — skipped when no Docker;</li>
 *   <li>{@code E2E_PG=true} environment variable — explicit opt-in.</li>
 * </ul>
 *
 * <p>Run with:</p>
 * <pre>E2E_PG=true ./mvnw test -Dtest=PgVectorE2EIT -DfailIfNoTests=false</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "E2E_PG", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.store=pgvector")
class PgVectorE2EIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_demo")
            .withUsername("ai")
            .withPassword("test");

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void vectorStoreIsPgVectorWithInitializedSchema() {
        assertThat(vectorStore).isInstanceOf(PgVectorStore.class);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + PgVectorStore.DEFAULT_TABLE_NAME, Integer.class);
        assertThat(count).isNotNull();
    }

    @Test
    void chatMemoryTableExistsOnPostgres() {
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE lower(table_name) = 'spring_ai_chat_memory'",
                Integer.class);
        assertThat(tables).isEqualTo(1);
    }
}
