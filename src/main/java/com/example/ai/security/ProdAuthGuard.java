package com.example.ai.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast guard for deployments: when {@code app.auth.required=true} (the
 * prod profile sets it), startup aborts unless an API key is configured — so a
 * missing {@code APP_API_KEY} can never leave a public deployment wide open by
 * accident. Local/demo profiles keep {@code required=false}.
 * OIDC (app.oidc.enabled + app.oidc.issuer-uri) satisfies the requirement too (spec R4).
 */
@Configuration
public class ProdAuthGuard {

    private final boolean required;
    private final String apiKey;
    private final boolean oidcEnabled;
    private final String issuerUri;

    public ProdAuthGuard(@Value("${app.auth.required:false}") boolean required,
                          @Value("${app.auth.api-key:}") String apiKeys,
                          @Value("${app.oidc.enabled:false}") boolean oidcEnabled,
                          @Value("${app.oidc.issuer-uri:}") String issuerUri) {
        this.required = required;
        this.apiKey = apiKeys == null ? "" : apiKeys.trim();
        this.oidcEnabled = oidcEnabled;
        this.issuerUri = issuerUri == null ? "" : issuerUri.trim();
    }

    @PostConstruct
    void requireApiKeyWhenAuthIsMandatory() {
        if (!required) {
            return;
        }
        boolean oidcConfigured = oidcEnabled && !issuerUri.isEmpty();
        if (ApiKeyAuthInterceptor.parseKeys(apiKey).isEmpty() && !oidcConfigured) {
            throw new IllegalStateException(
                    "app.auth.required=true but neither an API key (APP_API_KEY) nor OIDC "
                            + "(APP_OIDC_ENABLED + OIDC_ISSUER_URI) is configured.");
        }
    }
}
