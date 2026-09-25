package com.example.ai.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The OIDC mode (spec §3): an app-owned {@link JwtDecoder} plus explicitly
 * ordered security chains. Nothing here activates unless app.oidc.enabled=true,
 * so default mode keeps the exact current behaviour (R3).
 */
@Configuration
public class OidcSecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "app.oidc.enabled", havingValue = "true")
    JwtDecoder oidcJwtDecoder(
            @Value("${app.oidc.issuer-uri:}") String issuerUri,
            @Value("${app.oidc.public-key-location:}") String publicKeyLocation,
            @Value("${app.oidc.allowed-audiences:}") String allowedAudiences,
            ResourceLoader resourceLoader) {
        return createDecoder(issuerUri, publicKeyLocation, allowedAudiences, resourceLoader);
    }

    /**
     * Builds the decoder for both modes: public-key-location = CI mode
     * (no network; issuer-uri is only the expected iss), otherwise discovery
     * via issuer-uri. Always validates issuer + aud/azp allow-list + non-empty sub.
     */
    static JwtDecoder createDecoder(String issuerUri, String publicKeyLocation,
                                    String allowedAudiences, ResourceLoader resourceLoader) {
        boolean localKey = publicKeyLocation != null && !publicKeyLocation.isBlank();
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(localKey
                    ? "app.oidc.issuer-uri must be set when app.oidc.public-key-location is used (expected iss claim)"
                    : "app.oidc.enabled=true requires app.oidc.issuer-uri (discovery) or app.oidc.public-key-location (tests)");
        }
        Set<String> allowed = parseAllowList(allowedAudiences);
        NimbusJwtDecoder decoder;
        if (localKey) {
            decoder = NimbusJwtDecoder.withPublicKey(readPemPublicKey(resourceLoader, publicKeyLocation)).build();
        } else {
            decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
        }
        decoder.setJwtValidator(validator(issuerUri, allowed));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, Set<String> allowed) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        return new DelegatingOAuth2TokenValidator<>(defaults, token -> {
            String sub = token.getSubject();
            if (sub == null || sub.isBlank()) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "sub claim must be non-empty", null));
            }
            List<String> aud = token.getAudience();
            String azp = token.getClaimAsString("azp");
            boolean matched = (aud != null && aud.stream().anyMatch(allowed::contains))
                    || (azp != null && allowed.contains(azp));
            if (!matched) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "token aud/azp not in app.oidc.allowed-audiences", null));
            }
            return OAuth2TokenValidatorResult.success();
        });
    }

    private static Set<String> parseAllowList(String csv) {
        if (csv == null) {
            throw new IllegalStateException("app.oidc.allowed-audiences is required when OIDC mode is enabled");
        }
        Set<String> allowed = Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (allowed.isEmpty()) {
            throw new IllegalStateException("app.oidc.allowed-audiences is required when OIDC mode is enabled");
        }
        return allowed;
    }

    private static RSAPublicKey readPemPublicKey(ResourceLoader resourceLoader, String location) {
        try {
            String pem = new String(resourceLoader.getResource(location)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read app.oidc.public-key-location: " + location, e);
        }
    }
}
