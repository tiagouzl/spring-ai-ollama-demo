package com.example.ai.rag;

import com.example.ai.api.RagAnswer;
import com.example.ai.api.RagDebugDocument;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final double similarityThreshold;
    private final Counter questions;
    private final Counter emptyRetrievals;

    public RagService(ChatClient.Builder builder,
                      VectorStore vectorStore,
                      @Value("${app.rag.similarity-threshold:0.5}") double similarityThreshold,
                      MeterRegistry registry) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Answer grounded in the provided context. If the context does not contain the answer, say you don't know.")
                .build();
        this.questions = Counter.builder("app.rag.questions").description("RAG questions answered").register(registry);
        this.emptyRetrievals = Counter.builder("app.rag.empty").description("RAG answers without retrieval (no relevant context)").register(registry);
    }

    /**
     * Answers grounded in the top-2 retrieved chunks, returning the reply plus
     * the chunks' {@code source} metadata so callers can show where the answer
     * came from. No relevant context → answer without retrieval + empty sources
     * (never a forced hallucination); technical failures propagate to the
     * controller's sanitized 503.
     */
    public RagAnswer answerWithSources(String question) {
        questions.increment();
        var docs = similaritySearch(question);
        if (docs == null || docs.isEmpty()) {
            emptyRetrievals.increment();
            // No relevant context (nothing stored or nothing above the similarity
            // threshold) — still return LLM answer but with a hint; this is not an error
            String content = chatClient.prompt().user(question).call().content();
            return new RagAnswer(content
                    + "\n\n[Note: no relevant context found in vector store; answer is without retrieval.]",
                    List.of());
        }
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        String prompt = """
                Context:
                %s

                Question: %s

                Answer grounded in the context above. If the context does not contain the answer, say you don't know.
                """.formatted(context, question);
        String content = chatClient.prompt().user(prompt).call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("RAG: LLM returned empty content for question: " + question);
        }
        List<String> sources = docs.stream()
                .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("source", doc.getId())))
                .distinct()
                .toList();
        return new RagAnswer(content, sources);
    }

    /**
     * Returns the retrieved chunks (top-2) as stable API DTOs, without the LLM
     * call — the debug view of {@link #answer(String)}.
     */
    public List<RagDebugDocument> debugSearch(String question) {
        return similaritySearch(question).stream()
                .map(doc -> new RagDebugDocument(doc.getId(), doc.getText(), doc.getScore(), doc.getMetadata()))
                .toList();
    }

    private List<Document> similaritySearch(String query) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(2)
                .similarityThreshold(similarityThreshold)
                .build());
    }
}