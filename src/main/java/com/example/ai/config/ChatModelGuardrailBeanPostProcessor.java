package com.example.ai.config;

import com.example.ai.security.OutputGuardrail;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

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

    public ChatModelGuardrailBeanPostProcessor(ObjectProvider<OutputGuardrail> guardrail) {
        this.guardrail = guardrail;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ChatModel model && !(model instanceof GuardedOutputChatModel)) {
            return new GuardedOutputChatModel(model, guardrail.getObject());
        }
        return bean;
    }
}
