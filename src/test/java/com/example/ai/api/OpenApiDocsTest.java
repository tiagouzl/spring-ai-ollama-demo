package com.example.ai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the OpenAPI/Swagger availability: the spec must be served at
 * {@code /v3/api-docs} and the interactive UI at {@code /swagger-ui/index.html}.
 * The spec is parsed as JSON (not string-matched) so formatting, key order or
 * field quoting cannot break the assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @Test
    void openApiSpecIsServed() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = JSON.readTree(response.getBody());
        assertThat(root.path("openapi").asText()).isNotBlank();
        assertThat(root.path("paths").has("/ai/chat")).isTrue();
    }

    @Test
    void swaggerUiIsServed() {
        ResponseEntity<String> response = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .matches(t -> t.isCompatibleWith(MediaType.TEXT_HTML));
        assertThat(response.getBody()).contains("swagger-ui");
    }
}
