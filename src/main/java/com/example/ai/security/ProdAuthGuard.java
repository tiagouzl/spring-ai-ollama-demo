package com.example.ai.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast guard for deployments: when {@code app.auth.required=true} (the
 * prod profile sets it), startup aborts unless an API key is configured — so a
 * missing {@code APP_API_KEY} can never leave a public deployment wide open by
 * accident. Local/demo profiles keep {@code required=false}.
 */
@Configuration
public class ProdAuthGuard {

    private final boolean required;
    private final String apiKey;

    public ProdAuthGuard(@Value("${app.auth.required:false}") boolean required,
                         @Value("${app.auth.api-key:}") String apiKey) {
        this.required = required;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @PostConstruct
    void requireApiKeyWhenAuthIsMandatory() {
        if (required && apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "app.auth.required=true but no API key is configured. Set APP_API_KEY (or app.auth.api-key).");
        }
    }
}
