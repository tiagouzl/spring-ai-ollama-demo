package com.example.ai.rag;

import com.example.ai.api.RagDebugDocument;
import com.example.ai.api.RagRequest;
import com.example.ai.security.PromptGuard;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;
    private final PromptGuard promptGuard;

    public RagController(RagService ragService, PromptGuard promptGuard) {
        this.ragService = ragService;
        this.promptGuard = promptGuard;
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
    public List<RagDebugDocument> debugGet(@RequestParam(value = "question", defaultValue = "What is RAG?") String question) {
        return ragService.debugSearch(question);
    }

    @PostMapping("/ai/rag/debug")
    public List<RagDebugDocument> debugPost(@Valid @RequestBody RagRequest request) {
        return ragService.debugSearch(request.question());
    }

    private ResponseEntity<String> answer(String question) {
        promptGuard.validate(question);
        try {
            return ResponseEntity.ok(ragService.answer(question));
        } catch (Exception e) {
            // Never leak exception messages to clients — log the detail server-side
            // and return a fixed, actionable hint instead.
            log.warn("RAG request failed for question: {}", question, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("RAG is unavailable. Ensure Ollama is running and the embedding model is installed "
                            + "(ollama pull nomic-embed-text). Check the server logs for details.");
        }
    }
}