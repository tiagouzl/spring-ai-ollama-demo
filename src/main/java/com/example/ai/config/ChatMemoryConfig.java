package com.example.ai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-turn chat memory backed by {@link JdbcChatMemoryRepository} (file-based
 * HSQLDB at ./data/chat-memory by default) so conversations survive application
 * restarts. For production, point {@code spring.datasource.*} at PostgreSQL or
 * another JDBC database — the schema is created automatically when
 * {@code spring.ai.chat.memory.repository.jdbc.initialize-schema=always}.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }
}
