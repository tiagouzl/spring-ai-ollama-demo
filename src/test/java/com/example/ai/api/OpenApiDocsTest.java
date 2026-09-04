package com.example.ai.api;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the OpenAPI/Swagger availability: the spec must be served at
 * {@code /v3/api-docs} and the interactive UI at {@code /swagger-ui/index.html}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    @Test
    void openApiSpecIsServed() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"");
        assertThat(response.getBody()).contains("\"/ai/chat\"");
    }

    @Test
    void swaggerUiIsServed() {
        ResponseEntity<String> response = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Swagger");
    }
}