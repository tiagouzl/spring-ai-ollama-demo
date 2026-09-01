package com.example.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import com.example.ai.rag.RagService;
import com.example.ai.tools.DateTimeTools;
import com.example.ai.tools.MathTools;

import java.util.UUID;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final RagService ragService;

    public ChatController(ChatClient.Builder builder, RagService ragService) {
        // MessageWindowChatMemory retém as últimas N mensagens por conversa
        // (usa InMemoryChatMemoryRepository por padrão, sem persistência externa)
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
        this.ragService = ragService;

        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
    }

    /** Gera um novo id de sessão para começar uma conversa multi-turno. */
    @GetMapping("/ai/session")
    public String newSession() {
        return UUID.randomUUID().toString();
    }

    /** Chat de uma única mensagem (stateless). */
    @GetMapping("/ai/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "O que é Spring AI?") String message) {
        return chatClient.prompt(message).call().content();
    }

    /** Chat com streaming (SSE) - resposta em tempo real. */
    @GetMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam(value = "message", defaultValue = "Conte uma piada curta") String message) {
        return chatClient.prompt(message).stream().content();
    }

    /** Chat multi-turno com memória por sessão. */
    @GetMapping("/ai/chat/memory")
    public String chatMemory(@RequestParam("sessionId") String sessionId,
                             @RequestParam("message") String message) {
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(sessionId)
                .build();

        return chatClient.prompt()
                .advisors(memoryAdvisor)
                .user(message)
                .call()
                .content();
    }

    /** Chat com function calling / tool use - modelo pode chamar tools Java. */
    @GetMapping("/ai/chat/tools")
    public String chatWithTools(@RequestParam(value = "message", defaultValue = "What is the current date and time? Also, what is 15% of 200?") String message) {
        return chatClient.prompt()
                .tools(new DateTimeTools(), new MathTools())
                .user(message)
                .call()
                .content();
    }

    /** RAG — pergunta respondida com contexto dos docs em resources/docs/. */
    @GetMapping("/ai/rag")
    public String rag(@RequestParam(value = "question", defaultValue = "What is Spring AI and how does RAG work?") String question) {
        return ragService.answer(question);
    }

    /** RAG debug — retorna os chunks recuperados sem chamar LLM. */
    @GetMapping("/ai/rag/debug")
    public java.util.List<org.springframework.ai.document.Document> ragDebug(@RequestParam(value = "question", defaultValue = "What is RAG?") String question) {
        return ragService.debugSearch(question);
    }
}
