package com.example.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Global CORS configuration for the {@code /ai/**} endpoints so browser-based
 * frontends (React, Vue, etc.) can consume the API. Origins are configurable
 * via {@code app.cors.allowed-origins} (comma-separated, default {@code *} =
 * any origin — appropriate for a public demo API; narrow it down for production).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        // The CORS spec forbids combining a wildcard origin ("*") with credentials.
        // When the operator pinned concrete origins they may want credentialed
        // requests (cookies/auth headers), so enable credentials only then; for the
        // wildcard default we keep credentials off so a later "add credentials"
        // cannot silently produce an invalid (and rejected) configuration.
        boolean wildcard = Arrays.asList(origins).contains("*");
        registry.addMapping("/ai/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(!wildcard);
    }
}