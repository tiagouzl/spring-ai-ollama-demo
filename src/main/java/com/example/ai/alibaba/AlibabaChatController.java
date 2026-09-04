package com.example.ai.alibaba;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.ai.api.ChatRequest;
import com.example.ai.security.PromptGuard;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlibabaChatController {

    private static final Logger log = LoggerFactory.getLogger(AlibabaChatController.class);

    private final ChatClient ollamaChatClient;
    private final ObjectProvider<DashScopeChatModel> dashScopeProvider;
    private final String dashScopeApiKey;
    private final PromptGuard promptGuard;

    public AlibabaChatController(ChatClient.Builder ollamaBuilder,
                                 ObjectProvider<DashScopeChatModel> dashScopeProvider,
                                 @Value("${spring.ai.dashscope.api-key:}") String dashScopeApiKey,
                                 PromptGuard promptGuard) {
        // Ollama ChatClient is the default ChatClient.Builder (auto-configured for Ollama)
        this.ollamaChatClient = ollamaBuilder.build();
        this.dashScopeProvider = dashScopeProvider;
        this.dashScopeApiKey = dashScopeApiKey;
        this.promptGuard = promptGuard;
    }

    private boolean isDashScopeConfigured() {
        // Single source of truth — same rule as the bean condition.
        return DashScopeEnabledCondition.isEnabled(dashScopeApiKey);
    }

    @GetMapping("/ai/alibaba/chat")
    public ResponseEntity<String> alibabaChatGet(@RequestParam(value = "message", defaultValue = "Hello from Alibaba DashScope!") String message) {
        return alibabaChat(message);
    }

    @PostMapping("/ai/alibaba/chat")
    public ResponseEntity<String> alibabaChatPost(@Valid @RequestBody ChatRequest request) {
        return alibabaChat(request.message());
    }

    private ResponseEntity<String> alibabaChat(String message) {
        promptGuard.validate(message);
        if (!isDashScopeConfigured()) {
            return ResponseEntity.ok("""
                    [Alibaba DashScope not configured]
                    Set environment variable DASHSCOPE_API_KEY to enable this endpoint.
                    Example: export DASHSCOPE_API_KEY=sk-xxxx && mvn spring-boot:run
                    Get your key at: https://dashscope.console.aliyun.com/apiKey
                    Ollama fallback — answering with local model instead:

                    """ + ollamaChatClient.prompt(message).call().content());
        }
        DashScopeChatModel dashModel = dashScopeProvider.getIfAvailable();
        if (dashModel == null) {
            // Key is set but the bean was not created — a real misconfiguration.
            // Surface it instead of silently serving Ollama under HTTP 200.
            log.warn("DashScope API key is set but the DashScopeChatModel bean is not available");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("DashScope is configured but the model bean is unavailable. Check the server logs — no silent fallback.");
        }
        try {
            ChatClient dashClient = ChatClient.builder(dashModel).build();
            return ResponseEntity.ok(dashClient.prompt(message).call().content());
        } catch (Exception e) {
            // Do NOT mask the failure with a 200 + fallback text — APM/alerting must
            // see the error. Log the detail server-side; return a sanitized 502.
            log.warn("DashScope request failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("DashScope request failed. The error is surfaced instead of a silent fallback — check the server logs.");
        }
    }

    @GetMapping("/ai/alibaba/status")
    public String status() {
        if (!isDashScopeConfigured()) {
            return "Alibaba DashScope: NOT CONFIGURED (DASHSCOPE_API_KEY not set) — /ai/alibaba/chat will fallback to Ollama";
        }
        DashScopeChatModel dashModel = dashScopeProvider.getIfAvailable();
        if (dashModel == null) {
            return "Alibaba DashScope: API key set but DashScopeChatModel bean not created — check logs";
        }
        return "Alibaba DashScope: CONFIGURED — model: " + dashModel.getDefaultOptions();
    }
}