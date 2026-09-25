package com.example.ai.cache;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link SemanticCache} keying and tie-breaking:
 * <ul>
 *   <li>the cache is a <b>pool of candidate answers compared by cosine</b>,
 *       not a text-to-answer map — repeated stores grow the pool instead of
 *       overwriting, which is what makes different phrasings of the same
 *       question share a hit;</li>
 *   <li>entries that tie on similarity resolve to the <b>most recent</b> one,
 *       so an old answer cannot freeze a cache slot forever.</li>
 * </ul>
 * Uses a hand-rolled {@link EmbeddingModel} mock so the test runs with no
 * Spring context and no Ollama.
 */
class SemanticCacheUnitTest {

    /** all-ones vector => cosine 1.0 among "similar*" messages. */
    private static float[] allOnes() {
        float[] v = new float[4];
        Arrays.fill(v, 1.0f);
        return v;
    }

    /** orthogonal to all-ones (only index 0 set) => cosine 0.0 vs all-ones. */
    private static float[] orthogonal() {
        float[] v = new float[4];
        v[0] = 1.0f;
        return v;
    }

    private static SemanticCache newCache(EmbeddingModel model) {
        return newCache(model, new SimpleMeterRegistry());
    }

    private static SemanticCache newCache(EmbeddingModel model, MeterRegistry registry) {
        return new SemanticCache(model, true, 0.95, 3600, 1000, "memory", registry, null);
    }

    @Test
    void identicalEmbeddingsResolveToTheMostRecentAnswer() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        // Both messages produce the same embedding -> cosine 1.0 between them.
        when(model.embed(anyString())).thenReturn(allOnes());
        SemanticCache cache = newCache(model);

        cache.store("first phrasing", "answer A");
        cache.store("second phrasing", "answer B");

        // Both entries are kept (each store inserts; the cache never overwrites).
        assertThat(cache.size()).isEqualTo(2);

        // With identical embeddings the two entries tie on similarity, so the
        // tie-break must pick the most recent one — otherwise the first answer
        // would "freeze" forever. This is the regression this test locks.
        assertThat(cache.lookup("first phrasing")).contains("answer B");
        assertThat(cache.lookup("second phrasing")).contains("answer B");
    }

    @Test
    void textEqualityIsIrrelevantButRepeatedStoresGrowTheCache() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(allOnes());
        SemanticCache cache = newCache(model);

        // With a text-keyed map, storing the SAME message twice would keep
        // size == 1 (overwrite). With the opaque-id keying introduced to make
        // the cache truly semantic, each store is a new entry: the cache is a
        // pool of candidate answers compared by cosine, not a text->answer map.
        cache.store("same message", "answer 1");
        assertThat(cache.size()).isEqualTo(1);
        cache.store("same message", "answer 2");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void cacheIsIsolatedByClientNamespace() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(allOnes());
        SemanticCache cache = newCache(model);

        cache.store("same question", "client A answer", "client-a");
        cache.store("same question", "client B answer", "client-b");

        assertThat(cache.lookup("same question", "client-a")).contains("client A answer");
        assertThat(cache.lookup("same question", "client-b")).contains("client B answer");
    }

    @Test
    void orthogonalEmbeddingMissesCache() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("similar question")).thenReturn(allOnes());
        when(model.embed("other topic")).thenReturn(orthogonal());
        SemanticCache cache = newCache(model);

        cache.store("similar question", "answer A");

        assertThat(cache.lookup("other topic")).isEmpty();
        assertThat(cache.lookup("similar question")).contains("answer A");
    }

    @Test
    void textIsNotTheKey() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(allOnes());
        SemanticCache cache = newCache(model);

        cache.store("A", "answer A");
        cache.store("B", "answer B");
        cache.store("C", "answer C");

        // Three stores -> three entries (each is a separate insertion because
        // the cache never overwrites; lookups pick the nearest by cosine).
        assertThat(cache.size()).isEqualTo(3);
        // But every lookup resolves via similarity, not text equality.
        assertThat(cache.lookup("anything")).contains("answer C");
    }

    @Test
    void expiredEntriesAreNotServed() throws InterruptedException {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(allOnes());
        // ttl=1s: lookup must serve while fresh, then eagerly evict the entry
        // before matching once it ages past the TTL — a stale answer must never
        // be returned by the similarity loop.
        SemanticCache cache = new SemanticCache(model, true, 0.95, 1, 1000, "memory",
                new SimpleMeterRegistry(), null);

        cache.store("aged question", "fresh answer");
        assertThat(cache.lookup("aged question")).contains("fresh answer");

        Thread.sleep(1100);
        assertThat(cache.lookup("aged question")).isEmpty();
        assertThat(cache.size()).isZero(); // eagerly dropped on read, not just hidden
    }

    @Test
    void lookupsAreCountedAsHitMissAndBypass() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(allOnes());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SemanticCache cache = newCache(model, registry);

        cache.store("known question", "answer A");
        assertThat(cache.lookup("known question")).contains("answer A"); // hit
        assertThat(cache.lookup("   ")).isEmpty(); // bypass (blank)

        assertThat(registry.get("app.cache.semantic.lookup").tag("result", "hit").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("app.cache.semantic.lookup").tag("result", "bypass").counter().count()).isEqualTo(1.0);
    }
}
