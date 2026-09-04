package com.example.ai.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory semantic cache for chat replies: before calling the model, the
 * message is embedded and compared (cosine similarity) against previously stored
 * answers; a sufficiently similar past question returns the cached answer —
 * saving tokens/latency. Enabled via {@code app.cache.semantic.enabled} (default
 * {@code false}).
 * <p>
 * Deliberately <b>fail-safe</b>: any embedding/connection error simply bypasses
 * the cache (logged at debug), so a cache problem can never break a chat request.
 * Entries expire after {@code ttl-seconds} and the cache is capped at
 * {@code max-entries} (lazy eviction on store). Per-instance only — a production
 * deployment should use a shared store (e.g. Redis) for the same semantics.
 * </p>
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    record Entry(float[] embedding, String answer, Instant createdAt) {
    }

    private final EmbeddingModel embeddingModel;
    private final boolean enabled;
    private final double similarityThreshold;
    private final Duration ttl;
    private final int maxEntries;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public SemanticCache(EmbeddingModel embeddingModel,
                         @Value("${app.cache.semantic.enabled:false}") boolean enabled,
                         @Value("${app.cache.semantic.similarity-threshold:0.95}") double similarityThreshold,
                         @Value("${app.cache.semantic.ttl-seconds:3600}") long ttlSeconds,
                         @Value("${app.cache.semantic.max-entries:1000}") int maxEntries) {
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.maxEntries = maxEntries;
    }

    /**
     * Returns a cached answer for a semantically similar past question, if any.
     * Never throws — on any failure the cache is bypassed.
     */
    public Optional<String> lookup(String message) {
        if (!enabled || message == null || message.isBlank()) {
            return Optional.empty();
        }
        try {
            float[] query = embeddingModel.embed(message);
            long now = Instant.now().toEpochMilli();
            double best = -1.0;
            String bestAnswer = null;
            for (Entry entry : cache.values()) {
                if (now - entry.createdAt().toEpochMilli() > ttl.toMillis()) {
                    continue; // expired — lazily skipped (removed on next store)
                }
                double similarity = cosine(query, entry.embedding());
                if (similarity > best) {
                    best = similarity;
                    bestAnswer = entry.answer();
                }
            }
            if (best >= similarityThreshold) {
                log.info("[cache] semantic hit for message (similarity={})", String.format("%.2f", best));
                return Optional.ofNullable(bestAnswer);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("[cache] lookup failed, bypassing: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Stores the answer for a message. Fail-safe: never throws. */
    public void store(String message, String answer) {
        if (!enabled || message == null || message.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            if (cache.size() >= maxEntries) {
                evict();
            }
            cache.put(message, new Entry(embeddingModel.embed(message), answer, Instant.now()));
            log.debug("[cache] stored answer ({} entries)", cache.size());
        } catch (Exception e) {
            log.debug("[cache] store failed, skipping: {}", e.getMessage());
        }
    }

    private void evict() {
        long now = Instant.now().toEpochMilli();
        cache.entrySet().removeIf(e -> now - e.getValue().createdAt().toEpochMilli() > ttl.toMillis());
        if (cache.size() >= maxEntries) {
            log.info("[cache] max entries reached, clearing {} entries", cache.size());
            cache.clear();
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}