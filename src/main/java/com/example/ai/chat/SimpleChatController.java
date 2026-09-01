package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
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
    public String chatPost(@RequestBody ChatRequest request) {
        String message = request.message() != null ? request.message() : "What is Spring AI?";
        return chatClient.prompt(message).call().content();
    }
}
