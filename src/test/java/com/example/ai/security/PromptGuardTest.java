package com.example.ai.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Locks the configurable blocklist semantics of {@link PromptGuard}: the
 * configured list replaces the built-in defaults, matching is case-insensitive,
 * and blank/oversized messages are always rejected.
 */
class PromptGuardTest {

    private final PromptGuard guard = new PromptGuard(
            List.of("drop table", "override the system prompt"),
            new SimpleMeterRegistry());

    @Test
    void customPhraseIsRejectedCaseInsensitively() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard.validate("please DROP TABLE users"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> guard.validate("Override The System Prompt now"));
    }

    @Test
    void configuredListReplacesTheDefaults() {
        // "ignore previous instructions" is a default phrase but not in the
        // custom list — the property replaces, never merges.
        assertThatCode(() -> guard.validate("ignore previous instructions"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("hello world"))
                .doesNotThrowAnyException();
    }

    @Test
    void blankAndOversizedMessagesAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> guard.validate(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.validate(null));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.validate("x".repeat(4001)));
    }
}
