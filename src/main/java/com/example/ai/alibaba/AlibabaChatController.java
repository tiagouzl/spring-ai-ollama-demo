package com.example.ai.alibaba;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlibabaChatController {

    private final ChatClient ollamaChatClient;
    private final ObjectProvider<DashScopeChatModel> dashScopeProvider;
    private final String dashScopeApiKey;

    public AlibabaChatController(ChatClient.Builder ollamaBuilder,
                                 ObjectProvider<DashScopeChatModel> dashScopeProvider,
                                 @Value("${spring.ai.dashscope.api-key:}") String dashScopeApiKey) {
        // Ollama ChatClient is the default ChatClient.Builder (auto-configured for Ollama)
        this.ollamaChatClient = ollamaBuilder.build();
        this.dashScopeProvider = dashScopeProvider;
        this.dashScopeApiKey = dashScopeApiKey;
    }

    private boolean isDashScopeConfigured() {
        // Keep in sync with DashScopeEnabledCondition.DUMMY and application.yml dashscope.api-key default
        return dashScopeApiKey != null && !dashScopeApiKey.isBlank()
                && !DashScopeEnabledCondition.DUMMY.equals(dashScopeApiKey);
    }

    @GetMapping("/ai/alibaba/chat")
    public String alibabaChat(@RequestParam(value = "message", defaultValue = "Hello from Alibaba DashScope!") String message) {
        if (!isDashScopeConfigured()) {
            return """
                    [Alibaba DashScope not configured]
                    Set environment variable DASHSCOPE_API_KEY to enable this endpoint.
                    Example: export DASHSCOPE_API_KEY=sk-xxxx && mvn spring-boot:run
                    Get your key at: https://dashscope.console.aliyun.com/apiKey
                    Ollama fallback — answering with local model instead:

                    """ + ollamaChatClient.prompt(message).call().content();
        }
        DashScopeChatModel dashModel = dashScopeProvider.getIfAvailable();
        if (dashModel == null) {
            return "[DashScopeChatModel bean not available] Check spring.ai.dashscope.api-key and restart. Fallback to Ollama:\n\n"
                    + ollamaChatClient.prompt(message).call().content();
        }
        try {
            ChatClient dashClient = ChatClient.builder(dashModel).build();
            return dashClient.prompt(message).call().content();
        } catch (Exception e) {
            return "DashScope error: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "\n\nFallback to Ollama:\n\n" + ollamaChatClient.prompt(message).call().content();
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
