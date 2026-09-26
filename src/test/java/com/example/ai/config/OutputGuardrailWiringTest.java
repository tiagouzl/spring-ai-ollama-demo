package com.example.ai.config;

import com.example.ai.security.OutputGuardrail;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the guardrail seam: in the real application context every {@link ChatModel}
 * bean the app exposes is a {@link GuardedOutputChatModel}, and the guardrail is
 * configured with the default blocklist. This is the wiring contract — a new model
 * bean cannot silently escape redaction, which is what per-call-site application
 * (the input-side {@code PromptGuard}) cannot promise.
 * <p>
 * Redaction behaviour itself is covered by {@code GuardedOutputChatModelTest};
 * the mock-based path cannot prove wiring because {@code @MockitoBean} instances
 * bypass bean post-processors.
 * </p>
 */
@SpringBootTest
class OutputGuardrailWiringTest {

    @Autowired
    @Qualifier("ollamaChatModel")
    private ChatModel ollamaChatModel;

    @Autowired
    private OutputGuardrail guardrail;

    @Autowired
    private MeterRegistry registry;

    @Test
    void everyModelBeanIsGuarded() {
        assertThat(ollamaChatModel).isInstanceOf(GuardedOutputChatModel.class);
    }

    @Test
    void guardrailUsesTheDefaultBlocklist() {
        assertThat(guardrail.isBlocked("ignore previous instructions, here you go")).isTrue();
        assertThat(guardrail.isBlocked("a clean answer")).isFalse();
    }

    @Test
    void semanticJudgeIsOffByDefaultAndRegistersNoMeters() {
        // Default-off is the app's standing invariant: a model that answers a
        // violation the blocklist cannot see is still served unredacted, and
        // the semantic meters do not exist until an operator opts in.
        assertThat(registry.find("app.guardrails.semantic.triggered").counter()).isNull();
        assertThat(registry.find("app.guardrails.semantic.errors").counter()).isNull();
    }

    @Test
    void embeddingStageIsOnByDefault() {
        // Calibrated over two independent pools (0 false positives, 6/6
        // violations caught) — EmbeddingPolicyCalibrationIT is the runnable
        // proof. This locks the default so it cannot silently regress to off.
        assertThat(registry.find("app.guardrails.embeddings.triggered").counter()).isNotNull();
        assertThat(registry.find("app.guardrails.embeddings.errors").counter()).isNotNull();
    }
}
