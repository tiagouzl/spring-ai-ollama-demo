package com.example.ai.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic output guardrail: compares a model answer against labelled
 * violation examples (one small set per policy) with cosine similarity and
 * redacts anything at or above the threshold.
 * <p>
 * It replaces the LLM judge for machines that cannot afford (or run) a
 * competent judge: one embedding call, no generation, no model temperature to
 * misbehave, and the same answer always scores the same. Tuning it means
 * editing {@code guardrail/policy-examples.json} — human-readable, reviewable.
 * </p>
 * <p>
 * <b>Fails open</b> like the other guard stages: if the embedding model is
 * unavailable, the answer is served and {@code app.guardrails.embeddings.errors}
 * counts the miss. The threshold is a calibrated number, not a guess — see
 * {@code ANALISE.md} for the measured separation and {@code
 * EmbeddingPolicyCalibrationIT} to re-check it when the model changes.
 * </p>
 * <p>
 * Not a @Component-wired stage on its own: the decorator constructs it, because
 * it needs the same model layer the other stages use. Enabled and wired in
 * {@code ChatModelGuardrailBeanPostProcessor}.
 * </p>
 */
public class EmbeddingPolicyClassifier {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingPolicyClassifier.class);

    /** One policy and the violation examples that define it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(String name, String description, List<String> examples) {
    }

    private record PolicyVectors(String name, List<float[]> vectors) {
    }

    private final EmbeddingModel embeddingModel;
    private final float threshold;
    private final List<Policy> policies;
    private final boolean enabled;
    private final Counter triggered;
    private final Counter errors;

    private volatile List<PolicyVectors> index;

    public EmbeddingPolicyClassifier(EmbeddingModel embeddingModel, float threshold, List<Policy> policies,
                                     MeterRegistry registry, boolean enabled) {
        this.embeddingModel = embeddingModel;
        this.threshold = threshold;
        this.policies = policies == null ? List.of() : List.copyOf(policies);
        this.enabled = enabled;
        this.triggered = enabled
                ? Counter.builder("app.guardrails.embeddings.triggered")
                        .description("Answers redacted by the embedding policy classifier").register(registry)
                : null;
        this.errors = enabled
                ? Counter.builder("app.guardrails.embeddings.errors")
                        .description("Classifier failures that failed open (embedding model unavailable)")
                        .register(registry)
                : null;
    }

    /** Loads the policies from {@code guardrail/policy-examples.json} on the classpath. */
    public static List<Policy> loadDefaultPolicies(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("guardrail/policy-examples.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<Policy> parsed = new ArrayList<>();
            for (JsonNode node : root.path("policies")) {
                List<String> examples = new ArrayList<>();
                node.path("examples").forEach(e -> examples.add(e.asText()));
                parsed.add(new Policy(node.path("name").asText(), node.path("description").asText(), examples));
            }
            return parsed;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read guardrail/policy-examples.json", e);
        }
    }

    /**
     * True when the answer is at least {@code threshold} similar to any labelled
     * violation example. False when disabled, unindexable, or on any failure.
     */
    public boolean isViolation(String answer) {
        if (!enabled || answer == null || answer.isBlank()) {
            return false;
        }
        try {
            float[] vector = normalize(embeddingModel.embed(new Document(answer)));
            for (PolicyVectors policy : index()) {
                for (float[] example : policy.vectors()) {
                    if (cosine(vector, example) >= threshold) {
                        triggered.increment();
                        log.info("guardrail embeddings: {} matched {} at cosine >= {}",
                                "answer", policy.name(), threshold);
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            if (errors != null) {
                errors.increment();
            }
            log.warn("guardrail embeddings: classifier failed, failing open — {}", e.getMessage());
            return false;
        }
    }

    /** Lazily embeds the examples once, on first use. */
    private List<PolicyVectors> index() {
        List<PolicyVectors> current = index;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index == null) {
                List<PolicyVectors> built = new ArrayList<>();
                for (Policy policy : policies) {
                    if (policy.examples() == null) {
                        continue;
                    }
                    List<float[]> vectors = new ArrayList<>();
                    for (String example : policy.examples()) {
                        vectors.add(normalize(embeddingModel.embed(new Document(example))));
                    }
                    built.add(new PolicyVectors(policy.name(), vectors));
                }
                index = List.copyOf(built);
                log.info("guardrail embeddings: indexed {} policies ({} examples) at threshold {}",
                        built.size(), built.stream().mapToInt(p -> p.vectors().size()).sum(), threshold);
            }
            return index;
        }
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return vector;
        }
        float[] unit = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = (float) (vector[i] / norm);
        }
        return unit;
    }

    private static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0f;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return (float) dot; // both sides are unit vectors
    }
}
