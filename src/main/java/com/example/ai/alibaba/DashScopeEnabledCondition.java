package com.example.ai.alibaba;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that is true only when a real DashScope API key is configured.
 * <p>
 * Single source of truth for "is DashScope enabled?".
 * Checks {@code spring.ai.dashscope.api-key} — must be non-blank. In
 * {@code application.yml} the property defaults to {@code ${DASHSCOPE_API_KEY:}},
 * so it resolves to an empty string when the env var is absent, which disables
 * DashScope. Setting {@code DASHSCOPE_API_KEY} (or overriding the property in a
 * profile/test) enables it — no magic sentinel value like "dummy" is involved.
 * </p>
 */
public class DashScopeEnabledCondition implements Condition {

    static final String KEY = "spring.ai.dashscope.api-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty(KEY);
        return apiKey != null && !apiKey.isBlank();
    }
}
