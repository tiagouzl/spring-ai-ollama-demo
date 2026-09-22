package com.example.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the prod fail-fast guard: with {@code app.auth.required=true} the
 * context must refuse to start unless an API key is configured, so a missing
 * APP_API_KEY can never leave a deployment open by accident.
 */
class ProdAuthGuardTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ProdAuthGuard.class));

    @Test
    void requiredWithoutApiKeyFailsStartup() {
        runner.withPropertyValues("app.auth.required=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("APP_API_KEY");
                });
    }

    @Test
    void requiredWithApiKeyStarts() {
        runner.withPropertyValues("app.auth.required=true", "app.auth.api-key=secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void notRequiredWithoutApiKeyStarts() {
        runner.withPropertyValues("app.auth.required=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
