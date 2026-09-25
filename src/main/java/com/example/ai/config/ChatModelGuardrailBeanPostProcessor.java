package com.example.ai.config;

import com.example.ai.security.OutputGuardrail;
import com.example.ai.security.SemanticGuardrailJudge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.ai.chat.model.ChatModel;
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
    private final boolean semanticEnabled;
    private final Duration semanticTimeout;
    private final List<String> semanticPolicies;

    public ChatModelGuardrailBeanPostProcessor(ObjectProvider<OutputGuardrail> guardrail,
                                               @Value("${app.guardrails.semantic.enabled:false}") boolean semanticEnabled,
                                               @Value("${app.guardrails.semantic.timeout:5s}") Duration semanticTimeout,
                                               @Value("${app.guardrails.semantic.policies:#{null}}") List<String> semanticPolicies) {
        this.guardrail = guardrail;
        this.semanticEnabled = semanticEnabled;
        this.semanticTimeout = semanticTimeout;
        this.semanticPolicies = semanticPolicies;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ChatModel model && !(model instanceof GuardedOutputChatModel)) {
            OutputGuardrail blocklist = guardrail.getObject();
            // The judge is built here, from this model's own raw delegate, so it
            // can never re-enter the guardrail.
            SemanticGuardrailJudge judge = new SemanticGuardrailJudge(
                    model, semanticPolicies, semanticTimeout, semanticEnabled, blocklist.registry());
            return new GuardedOutputChatModel(model, blocklist, judge);
        }
        return bean;
    }
}
