package com.example.ai.rag;

import com.example.ai.api.RagRequest;
import jakarta.validation.Valid;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping("/ai/rag")
    public ResponseEntity<String> ragGet(@RequestParam(value = "question", defaultValue = "What is Spring AI and how does RAG work?") String question) {
        return answer(question);
    }

    @PostMapping("/ai/rag")
    public ResponseEntity<String> ragPost(@Valid @RequestBody RagRequest request) {
        return answer(request.question());
    }

    @GetMapping("/ai/rag/debug")
    public List<Document> debugGet(@RequestParam(value = "question", defaultValue = "What is RAG?") String question) {
        return ragService.debugSearch(question);
    }

    @PostMapping("/ai/rag/debug")
    public List<Document> debugPost(@Valid @RequestBody RagRequest request) {
        return ragService.debugSearch(request.question());
    }

    private ResponseEntity<String> answer(String question) {
        try {
            return ResponseEntity.ok(ragService.answer(question));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("RAG error: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + ". Ensure 'ollama pull nomic-embed-text' and Ollama is running.");
        }
    }
}
