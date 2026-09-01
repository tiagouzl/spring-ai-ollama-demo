package com.example.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.List;

@Configuration
public class RagConfig {

    @Bean
    public VectorStore vectorStore(@Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
                                   @Value("classpath:docs/spring-ai-overview.txt") Resource overview,
                                   @Value("classpath:docs/rag-pattern.txt") Resource rag,
                                   @Value("classpath:docs/ollama-local.txt") Resource ollama) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        // Ingest documents at startup; on CI without Ollama embeddings this will be skipped
        // (embedding call fails) and RAG endpoint will return a hint instead of crashing boot.
        try {
            List<Document> docs = List.of(
                    toDocument(overview, "spring-ai-overview"),
                    toDocument(rag, "rag-pattern"),
                    toDocument(ollama, "ollama-local")
            );
            store.add(docs);
        } catch (Exception e) {
            // Do not fail startup when Ollama embeddings are unavailable (e.g. CI).
            System.err.println("[RAG] Skipped document ingestion (embedding unavailable): " + e.getMessage());
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
