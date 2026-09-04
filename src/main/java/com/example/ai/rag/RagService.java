package com.example.ai.rag;

import com.example.ai.api.RagDebugDocument;
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

    public RagService(ChatClient.Builder builder,
                      VectorStore vectorStore,
                      @Value("${app.rag.similarity-threshold:0.5}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Answer grounded in the provided context. If the context does not contain the answer, say you don't know.")
                .build();
    }

    public String answer(String question) {
        var docs = similaritySearch(question);
        if (docs == null || docs.isEmpty()) {
            // No relevant context (nothing stored or nothing above the similarity
            // threshold) — still return LLM answer but with a hint; this is not an error
            return chatClient.prompt().user(question).call().content()
                    + "\n\n[Note: no relevant context found in vector store; answer is without retrieval.]";
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
        return content;
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