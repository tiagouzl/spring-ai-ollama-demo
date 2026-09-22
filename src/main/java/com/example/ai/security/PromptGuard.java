package com.example.ai.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic protection against prompt injection: rejects user messages that
 * contain classic jailbreak patterns ("ignore previous instructions", ...)
 * before they reach the model. The blocklist is configurable via
 * {@code app.prompt-guard.blocked-phrases} (comma-separated, case-insensitive).
 * <p>
 * This is a cheap first line of defence, not a real guardrails layer — a
 * determined attacker can bypass any blocklist. For production, pair it with a
 * dedicated guardrails solution and stricter input handling.
 * </p>
 * <p>
 * Deliberately NOT blocked by default: "you are now" — it false-positives on
 * ordinary sentences ("you are now free to configure the timeout"). Re-add via
 * {@code app.prompt-guard.blocked-phrases} if you want the broader net.
 * </p>
 */
@Component
public class PromptGuard {

    private static final String DEFAULT_BLOCKED_PHRASES =
            "ignore previous instructions, ignore all previous instructions, ignore the system prompt, "
                    + "disregard all previous instructions, new system prompt";

    private final List<String> blockedPhrases;
    private final Counter rejected;

    public PromptGuard(@Value("${app.prompt-guard.blocked-phrases:" + DEFAULT_BLOCKED_PHRASES + "}") List<String> blockedPhrases,
                       MeterRegistry registry) {
        this.blockedPhrases = blockedPhrases.stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .toList();
        this.rejected = Counter.builder("app.security.promptguard.rejected")
                .description("Requests rejected by the prompt-injection blocklist").register(registry);
    }

    /**
     * Throws {@link IllegalArgumentException} (→ 400 via the global handler) when
     * the message contains a blocked prompt-injection pattern. Blank messages are
     * left to Bean Validation (@NotBlank) which runs before this on POST bodies.
     */
    public void validate(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String phrase : blockedPhrases) {
            if (lower.contains(phrase)) {
                rejected.increment();
                throw new IllegalArgumentException("Message rejected: blocked prompt-injection pattern detected");
            }
        }
    }
}