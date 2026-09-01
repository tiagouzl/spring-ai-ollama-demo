package com.example.ai.chat;

import com.example.ai.api.ChatRequest;
import com.example.ai.tools.DateTimeTools;
import com.example.ai.tools.MathTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class ToolsChatController {

    private final ChatClient chatClient;

    public ToolsChatController(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
    }

    @GetMapping("/ai/chat/tools")
    public String toolsGet(@RequestParam(value = "message", defaultValue = "What is the current date and time? Also, what is 15% of 200?") String message) {
        return callWithTools(message);
    }

    @PostMapping("/ai/chat/tools")
    public String toolsPost(@RequestBody ChatRequest request) {
        String message = request.message() != null ? request.message() : "What is the current date and time? Also, what is 15% of 200?";
        return callWithTools(message);
    }

    private String callWithTools(String message) {
        return chatClient.prompt()
                .tools(new DateTimeTools(), new MathTools())
                .user(message)
                .call()
                .content();
    }
}
