package com.example.ai.chat;

import com.example.ai.api.MemoryChatRequest;
import com.example.ai.security.PromptGuard;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class MemoryChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final PromptGuard promptGuard;

    public MemoryChatController(ChatClient.Builder builder, ChatMemory chatMemory, PromptGuard promptGuard) {
        this.chatMemory = chatMemory;
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
        this.promptGuard = promptGuard;
    }

    @GetMapping("/ai/session")
    public String newSession() {
        return UUID.randomUUID().toString();
    }

    @GetMapping("/ai/chat/memory")
    public String memoryGet(@RequestParam("sessionId") String sessionId,
                            @RequestParam("message") String message) {
        return callWithMemory(sessionId, message);
    }

    @PostMapping("/ai/chat/memory")
    public String memoryPost(@Valid @RequestBody MemoryChatRequest request) {
        return callWithMemory(request.sessionId(), request.message());
    }

    private String callWithMemory(String sessionId, String message) {
        promptGuard.validate(message);
        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(sessionId)
                .build();
        return chatClient.prompt()
                .advisors(advisor)
                .user(message)
                .call()
                .content();
    }
}