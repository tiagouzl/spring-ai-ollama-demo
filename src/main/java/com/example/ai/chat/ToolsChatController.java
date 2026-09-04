package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import com.example.ai.security.PromptGuard;
import com.example.ai.tools.DateTimeTools;
import com.example.ai.tools.MathTools;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class ToolsChatController {

    private final ChatClient chatClient;
    private final PromptGuard promptGuard;

    public ToolsChatController(ChatClient.Builder builder, PromptGuard promptGuard) {
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
        this.promptGuard = promptGuard;
    }

    @GetMapping("/ai/chat/tools")
    public String toolsGet(@RequestParam(value = "message", defaultValue = "What is the current date and time? Also, what is 15% of 200?") String message) {
        return callWithTools(message);
    }

    @PostMapping("/ai/chat/tools")
    public String toolsPost(@Valid @RequestBody ChatRequest request) {
        return callWithTools(request.message());
    }

    private String callWithTools(String message) {
        promptGuard.validate(message);
        return chatClient.prompt()
                .tools(new DateTimeTools(), new MathTools())
                .user(message)
                .call()
                .content();
    }
}