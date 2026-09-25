package com.example.ai.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic protection for model <em>output</em>: scans a generated answer for
 * the same blocklist the input-side {@link PromptGuard} applies and reports
 * whether it tripped. Unlike {@code PromptGuard} it never throws — redaction is
 * the caller's job, so a single decision point serves both the blocking and the
 * streaming paths.
 * <p>
 * The blocklist is configurable via {@code app.prompt-guard.output-blocked-phrases}
 * (comma-separated, case-insensitive) and defaults to the input-side phrases, so
 * enabling this guardrail changes nothing until the lists are tuned.
 * </p>
 * <p>
 * A cheap first line of defence, not a real guardrails layer: only textual
 * content is scanned (tool-call arguments are not), and a determined model can
 * phrase a violation around any fixed blocklist.
 * </p>
 */
@Component
public class OutputGuardrail {

    /** Stable, machine-detectable prefix clients can match on. */
    public static final String REDACTED_PREFIX = "[output-guardrail]";

    private static final String DEFAULT_BLOCKED_PHRASES =
            "ignore previous instructions, ignore all previous instructions, ignore the system prompt, "
                    + "disregard all previous instructions, new system prompt";

    private final List<String> blockedPhrases;
    private final Counter triggered;

    public OutputGuardrail(@Value("${app.prompt-guard.output-blocked-phrases:#{null}}") List<String> blockedPhrases,
                           MeterRegistry registry) {
        // An absent property falls back to the input-side phrases; an explicitly
        // configured list is taken as-is, so an empty list disables redaction.
        List<String> source = blockedPhrases == null
                ? List.of(DEFAULT_BLOCKED_PHRASES.split(","))
                : blockedPhrases;
        this.blockedPhrases = source.stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .toList();
        this.triggered = Counter.builder("app.security.outputguardrail.triggered")
                .description("Model responses redacted by the output blocklist").register(registry);
    }

    /** True when {@code text} contains a blocked pattern; counts each trip. */
    public boolean isBlocked(String text) {
        if (text == null || text.isEmpty() || blockedPhrases.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : blockedPhrases) {
            if (lower.contains(phrase)) {
                triggered.increment();
                return true;
            }
        }
        return false;
    }

    /** The fixed replacement served instead of a blocked answer. */
    public static String redactedText() {
        return REDACTED_PREFIX
                + " Response blocked by output guardrail (app.prompt-guard.output-blocked-phrases).";
    }
}
