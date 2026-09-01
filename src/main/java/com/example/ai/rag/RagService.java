package com.example.ai.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Answer grounded in the provided context. If the context does not contain the answer, say you don't know.")
                .build();
    }

    public String answer(String question) {
        var docs = vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(2).build());
        if (docs == null || docs.isEmpty()) {
            // No relevant context — still return LLM answer but with a hint; this is not an error
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

    public java.util.List<org.springframework.ai.document.Document> debugSearch(String question) {
        return vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder().query(question).topK(2).build());
    }
}
