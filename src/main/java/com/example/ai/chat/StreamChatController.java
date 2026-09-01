package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
public class StreamChatController {

    private final ChatClient chatClient;

    public StreamChatController(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
    }

    @GetMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamGet(@RequestParam(value = "message", defaultValue = "Tell a short joke") String message) {
        return chatClient.prompt(message).stream().content();
    }

    @PostMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamPost(@Valid @RequestBody ChatRequest request) {
        return chatClient.prompt(request.message()).stream().content();
    }
}
