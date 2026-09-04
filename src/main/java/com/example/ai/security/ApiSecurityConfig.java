package com.example.ai.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the security interceptors on all {@code /ai/**} endpoints. Order
 * matters: authentication runs before rate limiting, so unauthenticated requests
 * are rejected with 401 without consuming rate-limit budget.
 */
@Configuration
public class ApiSecurityConfig implements WebMvcConfigurer {

    private final ApiKeyAuthInterceptor apiKeyAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public ApiSecurityConfig(ApiKeyAuthInterceptor apiKeyAuthInterceptor,
                             RateLimitInterceptor rateLimitInterceptor) {
        this.apiKeyAuthInterceptor = apiKeyAuthInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyAuthInterceptor).addPathPatterns("/ai/**");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/ai/**");
    }
}