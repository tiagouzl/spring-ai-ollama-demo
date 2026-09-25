package com.example.ai.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the chat-memory TTL purge: rows in {@code SPRING_AI_CHAT_MEMORY}
 * older than {@code app.chat-memory.ttl-hours} are deleted, recent rows
 * survive, and a non-positive TTL disables purging entirely. The context
 * runs with {@code ttl-hours=0} so the {@code @Scheduled} tick of the real
 * bean can never race the assertions — each test drives its own instance.
 */
@SpringBootTest(properties = "app.chat-memory.ttl-hours=0")
class ChatMemoryTtlPurgeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private final String conversationId = UUID.randomUUID().toString();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?", conversationId);
    }

    private void insertMessage(Instant timestamp) {
        jdbcTemplate.update(
                "INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp) VALUES (?, ?, ?, ?)",
                conversationId, "hello", "USER", Timestamp.from(timestamp));
    }

    private long rows() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                Long.class, conversationId);
        return count == null ? 0 : count;
    }

    @Test
    void messagesOlderThanTtlArePurged() {
        insertMessage(Instant.now().minus(8, ChronoUnit.DAYS));
        insertMessage(Instant.now().minus(1, ChronoUnit.DAYS));

        new ChatMemoryTtlPurge(jdbcTemplate, 168).purge();

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void nonPositiveTtlKeepsEverything() {
        insertMessage(Instant.now().minus(8, ChronoUnit.DAYS));
        insertMessage(Instant.now().minus(1, ChronoUnit.DAYS));

        new ChatMemoryTtlPurge(jdbcTemplate, 0).purge();

        assertThat(rows()).isEqualTo(2);
    }
}
