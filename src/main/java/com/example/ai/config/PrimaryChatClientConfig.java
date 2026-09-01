package com.example.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Resolves the NoUniqueBeanDefinitionException that occurs when both
 * Ollama and DashScope ChatModels exist.
 * <p>
 * Both Ollama and DashScope auto-configurations use
 * {@code @ConditionalOnProperty(matchIfMissing=true)}, so when
 * {@code spring.ai.model.chat} is not set they both create a bean.
 * We force {@code spring.ai.model.chat=ollama} in application.yml to keep
 * Ollama as the auto-configured model, but {@link com.example.ai.alibaba.DashScopeManualConfig}
 * still creates a second ChatModel manually when a real API key is set.
 * </p>
 * <p>
 * Spring AI's {@code ChatClientAutoConfiguration.chatClientBuilder} requires exactly
 * one ChatModel. With two, it throws NoUniqueBeanDefinitionException. This
 * configuration provides a {@code @Primary} ChatClient.Builder explicitly tied
 * to the Ollama model, so the auto-configuration backs off
 * ({@code @ConditionalOnMissingBean}) and the application can host both models
 * simultaneously — Ollama as primary for the generic ChatClient, DashScope
 * accessed via {@code ChatClient.builder(dashScopeChatModel)} in
 * {@link com.example.ai.alibaba.AlibabaChatController}.
 * </p>
 */
@Configuration
public class PrimaryChatClientConfig {

    @Bean
    @Primary
    public ChatClient.Builder chatClientBuilder(@Qualifier("ollamaChatModel") ChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel);
    }
}
