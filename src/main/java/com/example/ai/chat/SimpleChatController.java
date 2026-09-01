package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class SimpleChatController {

    private final ChatClient chatClient;

    public SimpleChatController(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
    }

    @GetMapping("/ai/chat")
    public String chatGet(@RequestParam(value = "message", defaultValue = "What is Spring AI?") String message) {
        return chatClient.prompt(message).call().content();
    }

    @PostMapping("/ai/chat")
    public String chatPost(@Valid @RequestBody ChatRequest request) {
        return chatClient.prompt(request.message()).call().content();
    }
}
