package com.example.ai.config;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Custom {@link OllamaApi} with explicit HTTP timeouts for both call paths:
 * <ul>
 *   <li><b>Synchronous</b> (RestClient): connect 10s / read 120s — LLM generation is slow,
 *       so the read timeout must be generous, but a dead Ollama server now fails fast
 *       instead of hanging forever.</li>
 *   <li><b>Streaming</b> (WebClient via {@link JdkClientHttpConnector}): same JDK client,
 *       180s read timeout between chunks.</li>
 * </ul>
 * <p>
 * The Ollama auto-configuration declares its own {@code ollamaApi} bean with
 * {@code @ConditionalOnMissingBean}, so defining ours here makes the auto-configuration
 * back off and both {@code ollamaChatModel} and {@code ollamaEmbeddingModel} pick up
 * the timeouts automatically.
 * </p>
 */
@Configuration
public class OllamaClientConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration SYNC_READ_TIMEOUT = Duration.ofSeconds(120);
    static final Duration STREAM_READ_TIMEOUT = Duration.ofSeconds(180);

    @Bean
    public OllamaApi ollamaApi(@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(SYNC_READ_TIMEOUT);

        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(STREAM_READ_TIMEOUT);

        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(connector))
                .build();
    }
}