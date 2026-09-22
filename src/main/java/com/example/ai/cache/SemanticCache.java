package com.example.ai.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-memory semantic cache for chat replies: before calling the model, the
 * message is embedded and compared (cosine similarity) against previously stored
 * answers; a sufficiently similar past question returns the cached answer —
 * saving tokens/latency. Enabled via {@code app.cache.semantic.enabled} (default
 * {@code false}).
 * <p>
 * Deliberately <b>fail-safe</b>: any embedding/connection error simply bypasses
 * the cache (logged at debug), so a cache problem can never break a chat request.
 * Entries expire after {@code ttl-seconds}; expired entries are evicted both on
 * read (so idle instances do not retain stale answers) and before a store that
 * would exceed {@code max-entries}.
 * <p>
 * Two backends, selected by {@code app.cache.semantic.store}:
 * <ul>
 *   <li>{@code memory} (default) — per-instance {@code ConcurrentHashMap};</li>
 *   <li>{@code redis} — entries as hashes ({@code semcache:{id}} →
 *       embedding/answer/timestamp) with the key TTL, so hits are shared across
 *       replicas. Any Redis failure <b>falls back to bypass</b> (the model is
 *       called normally).</li>
 * </ul>
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
    // Keys are opaque sequential ids, NOT the message text: lookups are by
    // cosine similarity over the embeddings, so two semantically equivalent
    // questions must share a slot and hit the same entry. Using the text as
    // key would create one entry per phrasing and defeat the cache.
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final Counter hits;
    private final Counter misses;
    private final Counter bypasses;
    private final boolean redis;
    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "semcache:";
    private static final String SEQ_KEY = "semcache:seq";

    public SemanticCache(EmbeddingModel embeddingModel,
                         @Value("${app.cache.semantic.enabled:false}") boolean enabled,
                         @Value("${app.cache.semantic.similarity-threshold:0.95}") double similarityThreshold,
                         @Value("${app.cache.semantic.ttl-seconds:3600}") long ttlSeconds,
                         @Value("${app.cache.semantic.max-entries:1000}") int maxEntries,
                         @Value("${app.cache.semantic.store:memory}") String store,
                         MeterRegistry registry,
                         StringRedisTemplate redisTemplate) {
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.maxEntries = maxEntries;
        this.redis = "redis".equalsIgnoreCase(store);
        this.redisTemplate = redisTemplate;
        // Fixed names, one static tag — no prompt text, user id or other
        // high-cardinality label ever reaches Prometheus.
        this.hits = Counter.builder("app.cache.semantic.lookup").tag("result", "hit")
                .description("Semantic cache lookups").register(registry);
        this.misses = Counter.builder("app.cache.semantic.lookup").tag("result", "miss")
                .description("Semantic cache lookups").register(registry);
        this.bypasses = Counter.builder("app.cache.semantic.lookup").tag("result", "bypass")
                .description("Semantic cache lookups").register(registry);
    }

    /**
     * Returns a cached answer for a semantically similar past question, if any.
     * Never throws — on any failure the cache is bypassed.
     */
    public Optional<String> lookup(String message) {
        if (!enabled || message == null || message.isBlank()) {
            bypasses.increment();
            return Optional.empty();
        }
        if (redis) {
            try {
                return redisLookup(message);
            } catch (Exception e) {
                log.debug("[cache] redis lookup failed, bypassing: {}", e.getMessage());
                bypasses.increment();
                return Optional.empty();
            }
        }
        try {
            float[] query = embeddingModel.embed(message);
            long now = Instant.now().toEpochMilli();
            // Drop expired entries eagerly on read so idle instances do not keep
            // stale answers resident until the next store crosses max-entries.
            cache.entrySet().removeIf(e -> now - e.getValue().createdAt().toEpochMilli() > ttl.toMillis());
            double best = -1.0;
            Instant bestCreatedAt = Instant.MIN;
            String bestAnswer = null;
            for (Entry entry : cache.values()) {
                double similarity = cosine(query, entry.embedding());
                // Strictly greater on similarity, but ties are broken by the
                // most recent entry: two phrasings with identical embeddings
                // are equally close, and the freshest answer should win.
                if (similarity > best
                        || (similarity == best && entry.createdAt().isAfter(bestCreatedAt))) {
                    best = similarity;
                    bestCreatedAt = entry.createdAt();
                    bestAnswer = entry.answer();
                }
            }
            if (best >= similarityThreshold) {
                log.info("[cache] semantic hit for message (similarity={})", String.format("%.2f", best));
                hits.increment();
                return Optional.ofNullable(bestAnswer);
            }
            misses.increment();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("[cache] lookup failed, bypassing: {}", e.getMessage());
            bypasses.increment();
            return Optional.empty();
        }
    }

    /** Stores the answer for a message. Fail-safe: never throws. */
    public void store(String message, String answer) {
        if (!enabled || message == null || message.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        if (redis) {
            try {
                redisStore(message, answer);
            } catch (Exception e) {
                log.debug("[cache] redis store failed, skipping: {}", e.getMessage());
            }
            return;
        }
        try {
            if (cache.size() >= maxEntries) {
                evict();
            }
            long id = nextId.incrementAndGet();
            cache.put(id, new Entry(embeddingModel.embed(message), answer, Instant.now()));
            log.debug("[cache] stored answer ({} entries)", cache.size());
        } catch (Exception e) {
            log.debug("[cache] store failed, skipping: {}", e.getMessage());
        }
    }

    /** Visible for testing: number of live entries currently held. */
    int size() {
        return cache.size();
    }

    /**
     * Shared lookup: scans the Redis hashes (bounded by max-entries, same as
     * the memory scan) and picks the nearest by cosine, freshest on ties.
     * Corrupt or wrong-dimension entries are skipped (deleted when unreadable).
     */
    private Optional<String> redisLookup(String message) {
        float[] query = embeddingModel.embed(message);
        var keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            misses.increment();
            return Optional.empty();
        }
        double best = -1.0;
        Instant bestCreatedAt = Instant.MIN;
        String bestAnswer = null;
        for (String key : keys) {
            try {
                Map<Object, Object> h = redisTemplate.opsForHash().entries(key);
                float[] emb = decodeEmbedding((String) h.get("emb"));
                String ans = (String) h.get("ans");
                String ts = (String) h.get("ts");
                if (emb == null || emb.length != query.length || ans == null || ts == null) {
                    redisTemplate.delete(key);
                    continue;
                }
                Instant createdAt = Instant.ofEpochMilli(Long.parseLong(ts));
                double similarity = cosine(query, emb);
                if (similarity > best
                        || (similarity == best && createdAt.isAfter(bestCreatedAt))) {
                    best = similarity;
                    bestCreatedAt = createdAt;
                    bestAnswer = ans;
                }
            } catch (RuntimeException e) {
                redisTemplate.delete(key);
            }
        }
        if (best >= similarityThreshold) {
            hits.increment();
            return Optional.ofNullable(bestAnswer);
        }
        misses.increment();
        return Optional.empty();
    }

    private void redisStore(String message, String answer) {
        var keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && keys.size() >= maxEntries) {
            log.info("[cache] max entries reached, clearing {} entries", keys.size());
            redisTemplate.delete(keys);
        }
        Long id = redisTemplate.opsForValue().increment(SEQ_KEY);
        String key = KEY_PREFIX + id;
        redisTemplate.opsForHash().putAll(key, Map.of(
                "emb", encodeEmbedding(embeddingModel.embed(message)),
                "ans", answer,
                "ts", String.valueOf(Instant.now().toEpochMilli())));
        redisTemplate.expire(key, ttl);
    }

    private static String encodeEmbedding(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(4 * v.length);
        buf.asFloatBuffer().put(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static float[] decodeEmbedding(String s) {
        if (s == null) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(s);
        if (bytes.length == 0 || bytes.length % 4 != 0) {
            return null;
        }
        float[] v = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).asFloatBuffer().get(v);
        return v;
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