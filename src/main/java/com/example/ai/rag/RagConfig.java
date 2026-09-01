package com.example.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.File;
import java.util.List;

@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public VectorStore vectorStore(@Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
                                   @Value("classpath:docs/spring-ai-overview.txt") Resource overview,
                                   @Value("classpath:docs/rag-pattern.txt") Resource rag,
                                   @Value("classpath:docs/ollama-local.txt") Resource ollama,
                                   @Value("${app.rag.persistence-path:./data/vector-store.json}") File persistenceFile) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        // Reuse previously computed embeddings when available — startup is faster
        // and works fully offline even before the first embedding call.
        if (persistenceFile.exists()) {
            try {
                store.load(persistenceFile);
                log.info("[RAG] Loaded persisted vector store from {} ({} bytes)",
                        persistenceFile.getAbsolutePath(), persistenceFile.length());
                return store;
            } catch (Exception e) {
                log.warn("[RAG] Could not load persisted vector store ({}); re-ingesting",
                        e.getMessage());
            }
        }

        // Ingest documents at startup; on CI without Ollama embeddings this will be skipped
        // (embedding call fails) and RAG endpoint will return a hint instead of crashing boot.
        try {
            List<Document> docs = List.of(
                    toDocument(overview, "spring-ai-overview"),
                    toDocument(rag, "rag-pattern"),
                    toDocument(ollama, "ollama-local")
            );
            // Split documents into token-based chunks so that long sources fit the
            // small local model's context window and retrieval returns focused passages.
            List<Document> chunks = new TokenTextSplitter().apply(docs);
            log.info("[RAG] Ingesting {} document(s) split into {} chunk(s)", docs.size(), chunks.size());
            store.add(chunks);
            // Persist embeddings so subsequent restarts skip the embedding calls.
            if (persistenceFile.getParentFile() != null) {
                persistenceFile.getParentFile().mkdirs();
            }
            store.save(persistenceFile);
            log.info("[RAG] Persisted vector store to {}", persistenceFile.getAbsolutePath());
        } catch (Exception e) {
            // Do not fail startup when Ollama embeddings are unavailable (e.g. CI).
            log.warn("[RAG] Skipped document ingestion (embedding unavailable): {}", e.getMessage());
        }
        return store;
    }

    private static Document toDocument(Resource resource, String id) {
        try {
            String text = new String(resource.getInputStream().readAllBytes());
            return new Document(text, java.util.Map.of("source", id));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + resource, e);
        }
    }
}
