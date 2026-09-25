package com.example.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the {@code app.post-only-prompts} contract: when enabled, GET requests
 * on {@code /ai/**} carrying a prompt parameter ({@code message} or
 * {@code question}) are rejected with 405 before reaching the controllers —
 * prompts must travel in the POST body, never in the URL (logs, proxies,
 * browser history). Prompt-free GETs and every POST keep working.
 */
class PromptGetGuardFilterTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockHttpServletResponse run(boolean postOnly, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PromptGetGuardFilter(postOnly, mapper).doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest get(String uri, String param) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (param != null) {
            request.setParameter(param, "hello");
        }
        return request;
    }

    @Test
    void messageParamIsRejectedWith405() throws Exception {
        MockHttpServletResponse response = run(true, get("/ai/chat", "message"));
        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentType()).contains("application/json");
    }

    @Test
    void questionParamIsRejectedWith405() throws Exception {
        MockHttpServletResponse response = run(true, get("/ai/rag", "question"));
        assertThat(response.getStatus()).isEqualTo(405);
    }

    @Test
    void promptFreeGetPassesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PromptGetGuardFilter(true, mapper).doFilter(get("/ai/session", null), response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void postWithPromptAlwaysPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/chat");
        request.setParameter("message", "hello");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PromptGetGuardFilter(true, mapper).doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void flagOffLeavesGetUntouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PromptGetGuardFilter(false, mapper).doFilter(get("/ai/chat", "message"), response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
