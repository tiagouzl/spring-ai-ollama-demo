package com.example.ai.config;

import com.example.ai.security.EmbeddingPolicyClassifier;
import com.example.ai.security.OutputGuardrail;
import com.example.ai.security.SemanticGuardrailJudge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Wraps every {@link ChatModel} bean in a {@link GuardedOutputChatModel}, so the
 * output guardrail covers Ollama, DashScope and any future model through one
 * seam. Per-call-site application (like the input-side {@code PromptGuard}) is
 * what let guardrail coverage drift; the model layer cannot drift.
 * <p>
 * Idempotent: an already-guarded bean is returned untouched, so a model
 * explicitly wrapped in a {@code @Bean} method is not double-wrapped.
 * </p>
 * <p>
 * The guardrail is resolved lazily through an {@link ObjectProvider} on purpose:
 * a bean post-processor that injects regular beans would force the
 * {@code MeterRegistry} (and its binders) into existence during the
 * post-processor phase, which silently drops the JVM meters from the Prometheus
 * scrape.
 * </p>
 */
@Component
public class ChatModelGuardrailBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<OutputGuardrail> guardrail;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final ObjectProvider<ObjectMapper> objectMapper;
    private final boolean semanticEnabled;
    private final Duration semanticTimeout;
    private final List<String> semanticPolicies;
    private final boolean embeddingsEnabled;
    private final float embeddingsThreshold;

    public ChatModelGuardrailBeanPostProcessor(ObjectProvider<OutputGuardrail> guardrail,
                                               ObjectProvider<EmbeddingModel> embeddingModel,
                                               ObjectProvider<ObjectMapper> objectMapper,
                                               @Value("${app.guardrails.semantic.enabled:false}") boolean semanticEnabled,
                                               @Value("${app.guardrails.semantic.timeout:5s}") Duration semanticTimeout,
                                               @Value("${app.guardrails.semantic.policies:#{null}}") List<String> semanticPolicies,
                                               @Value("${app.guardrails.embeddings.enabled:false}") boolean embeddingsEnabled,
                                               @Value("${app.guardrails.embeddings.threshold:0.85}") float embeddingsThreshold) {
        this.guardrail = guardrail;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.semanticEnabled = semanticEnabled;
        this.semanticTimeout = semanticTimeout;
        this.semanticPolicies = semanticPolicies;
        this.embeddingsEnabled = embeddingsEnabled;
        this.embeddingsThreshold = embeddingsThreshold;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ChatModel model && !(model instanceof GuardedOutputChatModel)) {
            OutputGuardrail blocklist = guardrail.getObject();
            // Both stages are built from this model's own raw delegate, so a
            // verdict can never re-enter the guardrail.
            SemanticGuardrailJudge judge = new SemanticGuardrailJudge(
                    model, semanticPolicies, semanticTimeout, semanticEnabled, blocklist.registry());
            EmbeddingPolicyClassifier embeddings = embeddingsEnabled
                    ? new EmbeddingPolicyClassifier(embeddingModel.getObject(), embeddingsThreshold,
                            EmbeddingPolicyClassifier.loadDefaultPolicies(objectMapper.getObject()),
                            blocklist.registry(), true)
                    : null;
            return new GuardedOutputChatModel(model, blocklist, judge, embeddings);
        }
        return bean;
    }
}
