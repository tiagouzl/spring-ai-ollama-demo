package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import com.example.ai.api.TopicSentiment;
import com.example.ai.security.PromptGuard;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

/**
 * Structured-output endpoint: the LLM reply is parsed into the typed
 * {@link TopicSentiment} record instead of being returned as raw text.
 * Demonstrates {@code ChatClient.call().entity(Class)}.
 */
@RestController
public class StructuredChatController {

    private final ChatClient chatClient;
    private final PromptGuard promptGuard;

    public StructuredChatController(ChatClient.Builder builder, PromptGuard promptGuard) {
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Answer with concise structured data.")
                .build();
        this.promptGuard = promptGuard;
    }

    @GetMapping("/ai/chat/structured")
    public TopicSentiment structuredGet(@RequestParam(value = "message",
            defaultValue = "Spring AI makes building AI applications easy") String message) {
        return callStructured(message);
    }

    @PostMapping("/ai/chat/structured")
    public TopicSentiment structuredPost(@Valid @RequestBody ChatRequest request) {
        return callStructured(request.message());
    }

    private TopicSentiment callStructured(String message) {
        promptGuard.validate(message);
        return chatClient.prompt().user(message).call().entity(TopicSentiment.class);
    }
}