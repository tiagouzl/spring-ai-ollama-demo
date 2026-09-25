package com.example.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the HTTP behaviour of the LLM bulkhead end-to-end: with
 * {@code app.llm.max-concurrent=1}, a second request arriving while the first
 * is still in flight gets 429 with an {@code ApiError} body, and the held
 * request completes normally once the model replies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.llm.max-concurrent=1")
class LlmBulkheadTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private HttpEntity<String> chatRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("{\"message\":\"hello\"}", headers);
    }

    @Test
    void secondConcurrentCallGets429WhileFirstCompletes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ollamaChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return response("slow reply");
        });

        String url = "http://localhost:" + port + "/ai/chat";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<String>> first = pool.submit(
                    () -> rest.postForEntity(url, chatRequest(), String.class));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            ResponseEntity<String> second = rest.postForEntity(url, chatRequest(), String.class);
            assertThat(second.getStatusCode().value()).isEqualTo(429);
            assertThat(second.getBody()).contains("max-concurrent");

            release.countDown();
            ResponseEntity<String> done = first.get(10, TimeUnit.SECONDS);
            assertThat(done.getStatusCode().value()).isEqualTo(200);
            assertThat(done.getBody()).isEqualTo("slow reply");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
