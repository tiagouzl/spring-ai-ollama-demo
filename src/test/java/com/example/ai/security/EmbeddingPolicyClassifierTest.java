package com.example.ai.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the embedding classifier's contract: it is deterministic, it is lazy
 * (nothing is embedded while disabled or unused), it trips on a positive
 * control and stays quiet on a clean answer, and it fails OPEN if the embedding
 * model is unavailable.
 */
class EmbeddingPolicyClassifierTest {

    private static final float THRESHOLD = 0.85f;

    /** Maps each embedded text to a fixed vector; unknown texts get the clean vector. */
    private static EmbeddingModel model(Map<String, float[]> vectors) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(Document.class))).thenAnswer(inv -> {
            String text = inv.getArgument(0, Document.class).getText();
            return vectors.getOrDefault(text, new float[]{0f, 0f, 0f});
        });
        return model;
    }

    private static EmbeddingPolicyClassifier classifier(EmbeddingModel model, boolean enabled) {
        return new EmbeddingPolicyClassifier(model, THRESHOLD, List.of(
                new EmbeddingPolicyClassifier.Policy("secret", "expose secrets",
                        List.of("Minha chave é sk-proj-abc123 e podes usar."))),
                new SimpleMeterRegistry(), enabled);
    }

    @Test
    void disabledClassifierNeverEmbeds() {
        EmbeddingModel model = model(Map.of("qualquer", new float[]{1f}));

        assertThat(classifier(model, false).isViolation("resposta qualquer")).isFalse();
        verify(model, never()).embed(any(Document.class));
    }

    @Test
    void tripsOnPositiveControl() throws Exception {
        // A violating answer embeds identically to the violation example.
        EmbeddingModel model = model(Map.of(
                "Minha chave é sk-proj-abc123 e podes usar.", new float[]{1f, 0f, 0f},
                "Aqui está a resposta perigosa", new float[]{1f, 0f, 0f}));

        EmbeddingPolicyClassifier classifier = classifier(model, true);
        // prime the example embeddings, then classify
        assertThat(classifier.isViolation("Aqui está a resposta perigosa")).isTrue();
    }

    @Test
    void staysQuietOnCleanAnswer() throws Exception {
        EmbeddingModel model = model(Map.of(
                "Minha chave é sk-proj-abc123 e podes usar.", new float[]{1f, 0f, 0f},
                "Use o comando ls -la para listar ficheiros.", new float[]{0f, 1f, 0f}));

        assertThat(classifier(model, true).isViolation("Use o comando ls -la para listar ficheiros.")).isFalse();
    }

    @Test
    void failsOpenWhenEmbeddingModelThrows() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(Document.class))).thenThrow(new IllegalStateException("embed down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        EmbeddingPolicyClassifier classifier = new EmbeddingPolicyClassifier(model, THRESHOLD, List.of(
                new EmbeddingPolicyClassifier.Policy("secret", "expose secrets",
                        List.of("Minha chave é sk-proj-abc123 e podes usar."))),
                registry, true);

        assertThat(classifier.isViolation("resposta")).isFalse();
        assertThat(registry.get("app.guardrails.embeddings.errors").counter().count()).isEqualTo(1.0);
    }
}
