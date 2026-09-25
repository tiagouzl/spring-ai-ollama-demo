package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keeps prompts out of URLs: when {@code app.post-only-prompts} is {@code
 * true} (the prod profile), GET requests on {@code /ai/**} carrying a prompt
 * parameter ({@code message} or {@code question}) are answered with 405
 * before reaching the controllers — query strings end up in access logs,
 * proxies and browser history, so prompts must travel in the POST body.
 * Prompt-free GETs ({@code /ai/session}, {@code /ai/alibaba/status},
 * {@code /ai/chat/memory}) and every POST are untouched.
 */
@Component
public class PromptGetGuardFilter extends OncePerRequestFilter {

    private final boolean postOnlyPrompts;
    private final ObjectMapper objectMapper;

    public PromptGetGuardFilter(@Value("${app.post-only-prompts:false}") boolean postOnlyPrompts,
                                ObjectMapper objectMapper) {
        this.postOnlyPrompts = postOnlyPrompts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!postOnlyPrompts || !"GET".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/ai/")
                || (request.getParameter("message") == null && request.getParameter("question") == null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ApiErrorWriter.write(response, objectMapper, 405, "Method Not Allowed",
                "Prompt-carrying GETs are disabled (app.post-only-prompts=true). Send the prompt in a POST body.", request);
    }
}
