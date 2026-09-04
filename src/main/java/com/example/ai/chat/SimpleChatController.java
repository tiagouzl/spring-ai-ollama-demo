package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import com.example.ai.cache.SemanticCache;
import com.example.ai.security.PromptGuard;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class SimpleChatController {

    private final ChatClient chatClient;
    private final PromptGuard promptGuard;
    private final SemanticCache semanticCache;

    public SimpleChatController(ChatClient.Builder builder, PromptGuard promptGuard, SemanticCache semanticCache) {
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
        this.promptGuard = promptGuard;
        this.semanticCache = semanticCache;
    }

    @GetMapping("/ai/chat")
    public String chatGet(@RequestParam(value = "message", defaultValue = "What is Spring AI?") String message) {
        return answer(message);
    }

    @PostMapping("/ai/chat")
    public String chatPost(@Valid @RequestBody ChatRequest request) {
        return answer(request.message());
    }

    private String answer(String message) {
        promptGuard.validate(message);
        // Semantic cache (opt-in): same/similar questions skip the model call.
        return semanticCache.lookup(message).orElseGet(() -> {
            String content = chatClient.prompt(message).call().content();
            semanticCache.store(message, content);
            return content;
        });
    }
}