package com.example.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the OIDC decoder contract (spec R7 + §3): issuer validation, aud/azp
 * allow-list, non-empty sub — in the CI mode (local public key, no network).
 */
class OidcJwtDecoderTest {

    private static final String ISSUER = TestTokens.ISSUER;

    private JwtDecoder decoder() {
        return OidcSecurityConfig.createDecoder(ISSUER, "classpath:oidc/test-public.pem",
                "spring-ai-demo", new DefaultResourceLoader());
    }

    @Test
    void validTokenDecodes() {
        Jwt jwt = decoder().decode(TestTokens.token(Map.of()));
        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
    }

    @Test
    void wrongIssuerRejected() {
        assertThatThrownBy(() -> decoder().decode(
                TestTokens.token(Map.of("iss", "https://evil.example/realms/test"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void audAndAzpOutsideAllowListRejected() {
        assertThatThrownBy(() -> decoder().decode(
                TestTokens.token(Map.of("aud", List.of("other-api"), "azp", "other-client"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void azpAloneMatchingAllowListIsAccepted() {
        assertThat(decoder().decode(
                TestTokens.token(Map.of("aud", List.of("other-api")))).getSubject())
                .isEqualTo("user-1");
    }

    @Test
    void emptyOrMissingSubRejected() {
        assertThatThrownBy(() -> decoder().decode(TestTokens.token(Map.of("sub", ""))))
                .isInstanceOf(JwtException.class);
        Map<String, Object> nullSub = new java.util.LinkedHashMap<>();
        nullSub.put("sub", null);
        assertThatThrownBy(() -> decoder().decode(TestTokens.token(nullSub)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenRejected() {
        assertThatThrownBy(() -> decoder().decode(
                TestTokens.token(Map.of("exp", new Date(System.currentTimeMillis() - 60_000)))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedByAnotherKeyRejected() {
        assertThatThrownBy(() -> decoder().decode(TestTokens.tokenSignedByOtherKey(Map.of())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void misconfigurationsFailFastAtStartup() {
        assertThatThrownBy(() -> OidcSecurityConfig.createDecoder(
                "", "classpath:oidc/test-public.pem", "spring-ai-demo", new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");
        assertThatThrownBy(() -> OidcSecurityConfig.createDecoder(
                ISSUER, "classpath:oidc/test-public.pem", "  ", new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-audiences");
        assertThatThrownBy(() -> OidcSecurityConfig.createDecoder(
                "", "", "spring-ai-demo", new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
    }
}
