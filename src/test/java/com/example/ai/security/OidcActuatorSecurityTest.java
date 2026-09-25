package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks spec R6 in OIDC mode: metrics and prometheus require a Bearer JWT,
 * health stays public, and no API key is involved.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo"
})
class OidcActuatorSecurityTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void metricsWithoutTokenIs401() {
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void metricsWithTokenIs200() {
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(bearer(TestTokens.token(Map.of()))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void prometheusWithTokenIs200AndHealthStaysPublic() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("warm")))));
        rest.getForObject("/ai/chat?message=warm", String.class); // records http metrics (mirrors ObservabilityTest)

        HttpHeaders scrape = bearer(TestTokens.token(Map.of()));
        scrape.setAccept(List.of(MediaType.TEXT_PLAIN));
        ResponseEntity<String> prometheus = rest.exchange("/actuator/prometheus",
                HttpMethod.GET, new HttpEntity<>(scrape), String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("http_server_requests_seconds_count");

        ResponseEntity<String> health = rest.exchange("/actuator/health",
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("UP");
    }
}
