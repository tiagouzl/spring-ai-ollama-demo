package com.example.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the persistent chat-memory behaviour: messages exchanged through
 * /ai/chat/memory must be stored in the JDBC repository (SPRING_AI_CHAT_MEMORY
 * table in the file-based HSQLDB database), so conversations survive restarts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersistentChatMemoryTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OllamaChatModel ollamaChatModel;

    private static ChatResponse mockedResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test
    void memoryMessagesArePersistedToJdbcRepository() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(mockedResponse("persisted reply"));

        String sessionId = rest.getForObject("http://localhost:" + port + "/ai/session", String.class);
        assertThat(sessionId).isNotBlank();

        rest.getForObject("http://localhost:" + port
                + "/ai/chat/memory?sessionId=" + sessionId + "&message=My%20name%20is%20Tiago", String.class);
        rest.getForObject("http://localhost:" + port
                + "/ai/chat/memory?sessionId=" + sessionId + "&message=What%20is%20my%20name%3F", String.class);

        verify(ollamaChatModel, times(2)).call(any(Prompt.class));

        Integer storedMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                Integer.class, sessionId);

        // 2 user messages + 2 assistant replies persisted for this conversation
        assertThat(storedMessages).isGreaterThanOrEqualTo(4);
    }
}