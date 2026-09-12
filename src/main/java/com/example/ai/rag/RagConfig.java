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
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public VectorStore vectorStore(@Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
                                   @Value("classpath:docs/spring-ai-overview.txt") Resource overview,
                                   @Value("classpath:docs/rag-pattern.txt") Resource rag,
                                   @Value("classpath:docs/ollama-local.txt") Resource ollama,
                                   @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}") String embedderModel,
                                   @Value("${app.rag.persistence-path:./data/vector-store.json}") File persistenceFile) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File metaFile = metaFileFor(persistenceFile);

        // Reuse previously computed embeddings when available — startup is faster
        // and works fully offline even before the first embedding call. Skip the
        // cached store if it was built with a different embedding model, otherwise
        // we would answer against vectors that no longer match the current embedder.
        if (persistenceFile.exists() && embedderMatches(metaFile, embedderModel)) {
            try {
                store.load(persistenceFile);
                log.info("[RAG] Loaded persisted vector store from {} ({} bytes, embedder={})",
                        persistenceFile.getAbsolutePath(), persistenceFile.length(), embedderModel);
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
            log.info("[RAG] Ingesting {} document(s) split into {} chunk(s) with embedder {}",
                    docs.size(), chunks.size(), embedderModel);
            store.add(chunks);
            // Persist embeddings so subsequent restarts skip the embedding calls.
            if (persistenceFile.getParentFile() != null) {
                persistenceFile.getParentFile().mkdirs();
            }
            store.save(persistenceFile);
            writeEmbedderMeta(metaFile, embedderModel);
            log.info("[RAG] Persisted vector store to {}", persistenceFile.getAbsolutePath());
        } catch (Exception e) {
            // Do not fail startup when Ollama embeddings are unavailable (e.g. CI).
            log.warn("[RAG] Skipped document ingestion (embedding unavailable): {}", e.getMessage());
        }
        return store;
    }

    private static File metaFileFor(File persistenceFile) {
        return new File(persistenceFile.getParentFile(), persistenceFile.getName() + ".embedder");
    }

    private static boolean embedderMatches(File metaFile, String embedderModel) {
        if (!metaFile.exists()) {
            return false; // legacy cache with no version stamp — treat as stale
        }
        try {
            return embedderModel.equals(Files.readString(metaFile.toPath()).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeEmbedderMeta(File metaFile, String embedderModel) {
        try {
            File parent = metaFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.writeString(metaFile.toPath(), embedderModel);
        } catch (IOException e) {
            log.warn("[RAG] Could not write embedder meta to {}: {}", metaFile.getAbsolutePath(), e.getMessage());
        }
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
