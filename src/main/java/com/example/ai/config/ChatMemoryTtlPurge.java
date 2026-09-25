package com.example.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Bounds chat-memory storage: deletes rows in {@code SPRING_AI_CHAT_MEMORY}
 * older than {@code app.chat-memory.ttl-hours} (default 168 = 7 days), once
 * an hour and at startup. Non-positive values disable the purge. Uses the
 * existing {@code (conversation_id, "timestamp")} index, so the delete stays
 * cheap on both HSQL (demo) and PostgreSQL (prod). The timestamp column
 * quoting mirrors Spring AI's own {@code JdbcChatMemoryRepositoryDialect}:
 * HSQL folds the unquoted name, PostgreSQL needs the quoted lowercase one.
 */
@Component
public class ChatMemoryTtlPurge {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryTtlPurge.class);

    private final JdbcTemplate jdbcTemplate;
    private final long ttlHours;
    // ponytail: HSQL/Postgres product names only (this repo's targets) — add a
    // branch mirroring JdbcChatMemoryRepositoryDialect.from if MySQL/Oracle join.
    private final String timestampColumn;

    public ChatMemoryTtlPurge(JdbcTemplate jdbcTemplate,
                              @Value("${app.chat-memory.ttl-hours:168}") long ttlHours) {
        this.jdbcTemplate = jdbcTemplate;
        this.ttlHours = ttlHours;
        String product = null;
        try {
            product = JdbcUtils.extractDatabaseMetaData(
                    jdbcTemplate.getDataSource(), DatabaseMetaData::getDatabaseProductName);
        } catch (MetaDataAccessException e) {
            log.debug("Could not read database product name; defaulting to quoted timestamp column", e);
        }
        this.timestampColumn = "HSQL Database Engine".equals(product) ? "timestamp" : "\"timestamp\"";
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void purge() {
        if (ttlHours <= 0) {
            return;
        }
        Timestamp cutoff = Timestamp.from(Instant.now().minus(ttlHours, ChronoUnit.HOURS));
        int purged = jdbcTemplate.update(
                "DELETE FROM SPRING_AI_CHAT_MEMORY WHERE " + timestampColumn + " < ?", cutoff);
        if (purged > 0) {
            log.info("Purged {} chat-memory messages older than {}h", purged, ttlHours);
        }
    }
}
