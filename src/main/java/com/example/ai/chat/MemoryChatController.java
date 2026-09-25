package com.example.ai.chat;

import com.example.ai.api.MemoryChatRequest;
import com.example.ai.security.ClientIdentity;
import com.example.ai.security.PromptGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class MemoryChatController {
    // Conversation ids are derived per caller namespace before persistence. This
    // prevents a session id from one client aliasing another client's memory.
    // Strong user identity/authorization still requires OIDC/JWT; see README.

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final PromptGuard promptGuard;

    public MemoryChatController(ChatClient.Builder builder, ChatMemory chatMemory, PromptGuard promptGuard) {
        this.chatMemory = chatMemory;
        this.chatClient = builder.defaultSystem(ChatPrompts.DEFAULT).build();
        this.promptGuard = promptGuard;
    }

    @GetMapping("/ai/session")
    public String newSession() {
        return UUID.randomUUID().toString();
    }

    @GetMapping("/ai/chat/memory")
    public String memoryGet(@RequestParam("sessionId") String sessionId,
                            @RequestParam("message") String message,
                            HttpServletRequest request) {
        return callWithMemory(sessionId, message, request);
    }

    @PostMapping("/ai/chat/memory")
    public String memoryPost(@Valid @RequestBody MemoryChatRequest request,
                             HttpServletRequest servletRequest) {
        return callWithMemory(request.sessionId(), request.message(), servletRequest);
    }

    /**
     * Deletes the stored conversation behind a session id, scoped to the
     * caller's client namespace (same derivation as the chat endpoints), so
     * one client can never wipe another client's memory. Idempotent: unknown
     * sessions answer 204 as well.
     */
    @DeleteMapping("/ai/chat/memory/{sessionId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String sessionId,
                                                   HttpServletRequest request) {
        requireSession(sessionId);
        String conversationId = ClientIdentity.conversationId(
                ClientIdentity.namespaceFor(request), sessionId);
        chatMemory.clear(conversationId);
        return ResponseEntity.noContent().build();
    }

    private String callWithMemory(String sessionId, String message, HttpServletRequest request) {
        requireSession(sessionId);
        promptGuard.validate(message);
        String conversationId = ClientIdentity.conversationId(
                ClientIdentity.namespaceFor(request), sessionId);
        var advisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        return chatClient.prompt()
                .advisors(a -> a.advisors(advisor)
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(message)
                .call()
                .content();
    }

    private static void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new IllegalArgumentException("sessionId is required and must be at most 128 characters");
        }
    }
}