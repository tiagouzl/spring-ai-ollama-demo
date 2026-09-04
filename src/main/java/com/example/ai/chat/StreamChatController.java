package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import com.example.ai.security.PromptGuard;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
public class StreamChatController {

    private final ChatClient chatClient;
    private final PromptGuard promptGuard;

    public StreamChatController(ChatClient.Builder builder, PromptGuard promptGuard) {
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
        this.promptGuard = promptGuard;
    }

    @GetMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamGet(@RequestParam(value = "message", defaultValue = "Tell a short joke") String message) {
        promptGuard.validate(message);
        return chatClient.prompt(message).stream().content();
    }

    @PostMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamPost(@Valid @RequestBody ChatRequest request) {
        promptGuard.validate(request.message());
        return chatClient.prompt(request.message()).stream().content();
    }
}