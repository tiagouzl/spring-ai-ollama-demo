package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import com.example.ai.security.PromptGuard;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class SimpleChatController {

    private final ChatClient chatClient;
    private final PromptGuard promptGuard;

    public SimpleChatController(ChatClient.Builder builder, PromptGuard promptGuard) {
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
        this.promptGuard = promptGuard;
    }

    @GetMapping("/ai/chat")
    public String chatGet(@RequestParam(value = "message", defaultValue = "What is Spring AI?") String message) {
        promptGuard.validate(message);
        return chatClient.prompt(message).call().content();
    }

    @PostMapping("/ai/chat")
    public String chatPost(@Valid @RequestBody ChatRequest request) {
        promptGuard.validate(request.message());
        return chatClient.prompt(request.message()).call().content();
    }
}