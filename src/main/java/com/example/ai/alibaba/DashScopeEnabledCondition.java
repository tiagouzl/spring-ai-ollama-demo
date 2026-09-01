package com.example.ai.alibaba;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that is true only when a real DashScope API key is configured.
 * <p>
 * Single source of truth for "is DashScope enabled?".
 * Checks {@code spring.ai.dashscope.api-key} — must be non-blank and not the
 * dummy fallback defined in {@code application.yml} ({@code ${DASHSCOPE_API_KEY:dummy}}).
 * If you change the dummy value in {@code application.yml}, update {@link #DUMMY} here as well.
 * </p>
 */
public class DashScopeEnabledCondition implements Condition {

    static final String KEY = "spring.ai.dashscope.api-key";
    static final String DUMMY = "dummy";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String apiKey = env.getProperty(KEY);
        return apiKey != null && !apiKey.isBlank() && !DUMMY.equals(apiKey);
    }
}
