package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import com.example.ai.cache.SemanticCache;
import com.example.ai.security.ClientIdentity;
import com.example.ai.security.PromptGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class SimpleChatController {

    private final ChatClient chatClient;
    private final PromptGuard promptGuard;
    private final SemanticCache semanticCache;

    public SimpleChatController(ChatClient.Builder builder, PromptGuard promptGuard, SemanticCache semanticCache) {
        this.chatClient = builder.defaultSystem(ChatPrompts.DEFAULT).build();
        this.promptGuard = promptGuard;
        this.semanticCache = semanticCache;
    }

    @GetMapping("/ai/chat")
    public String chatGet(@RequestParam(value = "message", defaultValue = "What is Spring AI?") String message,
                          HttpServletRequest request) {
        return answer(message, request);
    }

    @PostMapping("/ai/chat")
    public String chatPost(@Valid @RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        return answer(request.message(), servletRequest);
    }

    private String answer(String message, HttpServletRequest request) {
        promptGuard.validate(message);
        String clientNamespace = ClientIdentity.namespaceFor(request);
        // Semantic cache (opt-in): same/similar questions skip the model call.
        return semanticCache.lookup(message, clientNamespace).orElseGet(() -> {
            String content = chatClient.prompt(message).call().content();
            semanticCache.store(message, content, clientNamespace);
            return content;
        });
    }
}