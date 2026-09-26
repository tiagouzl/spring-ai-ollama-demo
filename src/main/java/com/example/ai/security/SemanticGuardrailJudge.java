package com.example.ai.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Second-stage guardrail: asks the same local model whether an answer violates a
 * policy, closing what a fixed blocklist cannot (a violation phrased around any
 * fixed list). Opt-in via {@code app.guardrails.semantic.enabled} — off by
 * default, so the app's behaviour is unchanged until an operator accepts the
 * extra latency.
 * <p>
 * <b>Fails open by design.</b> A judge that is slow, down, or unparseable must
 * never take an endpoint with it, so every failure returns "no violation" and
 * increments {@code app.guardrails.semantic.errors} — a silent loss of
 * protection is an alertable metric, a permanent 200-blocked app is not.
 * </p>
 * <p>
 * Deliberately not a bean with an injected {@code ChatModel}: that would hand it
 * the already-guarded model and recurse. It is constructed by
 * {@code GuardedOutputChatModel} with its own raw delegate.
 * </p>
 * <p>
 * ponytail: the timeout is enforced with a bounded future on the common pool —
 * fine for a demo, move to a dedicated executor if judge latency ever matters
 * under load.
 * </p>
 */
public class SemanticGuardrailJudge {

    private static final Logger log = LoggerFactory.getLogger(SemanticGuardrailJudge.class);

    private static final String INSTRUCTION =
            "You are a strict content-safety classifier. Answer with exactly one word: sim or nao.";

    private final ChatModel delegate;
    private final List<String> policies;
    private final Duration timeout;
    private final boolean enabled;
    private final Counter triggered;
    private final Counter errors;

    public SemanticGuardrailJudge(ChatModel delegate, List<String> policies, Duration timeout,
                                  boolean enabled, MeterRegistry registry) {
        this.delegate = delegate;
        this.policies = policies == null || policies.isEmpty() ? List.of() : List.copyOf(policies);
        this.timeout = timeout;
        this.enabled = enabled;
        // Meters exist only when the judge is on, so the default Prometheus
        // scrape stays exactly as it was before this feature.
        this.triggered = enabled
                ? Counter.builder("app.guardrails.semantic.triggered")
                        .description("Answers redacted by the semantic guardrail judge").register(registry)
                : null;
        this.errors = enabled
                ? Counter.builder("app.guardrails.semantic.errors")
                        .description("Judge failures that failed open (timeout, error, unparseable verdict)")
                        .register(registry)
                : null;
    }

    /** True when the judge says the answer violates a policy; false on any failure. */
    public boolean isViolation(String question, String answer) {
        if (!enabled) {
            return false;
        }
        long startedNanos = System.nanoTime();
        try {
            String verdict = askJudge(question, answer);
            Boolean violation = parse(verdict);
            if (violation == null) {
                countError();
                log.info("semantic guardrail: UNPARSEABLE verdict after {}ms, failing open",
                        millisSince(startedNanos));
                return false;
            }
            if (violation) {
                triggered.increment();
            }
            // Every verdict is logged: a no-op judge (a small model answering
            // "nao" to everything) is otherwise indistinguishable from a
            // healthy one, and silent in metrics.
            log.info("semantic guardrail: verdict={} in {}ms", violation ? "VIOLATION" : "clean",
                    millisSince(startedNanos));
            return violation;
        } catch (RuntimeException e) {
            countError();
            log.warn("semantic guardrail: judge failed after {}ms, failing open — {}",
                    millisSince(startedNanos), e.getMessage());
            return false;
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String askJudge(String question, String answer) {
        // Temperature 0: a verdict that changes between identical calls is not
        // a verdict. The model's own default (0.7 in this app) is for prose.
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(INSTRUCTION),
                new UserMessage("Políticas a verificar:\n- " + String.join("\n- ", policies)
                        + "\n\nPergunta: " + question
                        + "\nResposta: " + answer
                        + "\n\nA resposta viola alguma das políticas? (sim/não)")),
                ChatOptions.builder().temperature(0.0).build());
        try {
            ChatResponse response = CompletableFuture
                    .supplyAsync(() -> delegate.call(prompt))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return textOf(response);
        } catch (TimeoutException e) {
            throw new IllegalStateException("semantic guardrail judge timed out after " + timeout, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("semantic guardrail judge failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("semantic guardrail judge interrupted", e);
        }
    }

    /** null means "unparseable" — the caller counts an error and fails open. */
    private static Boolean parse(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return null;
        }
        String first = verdict.trim().toLowerCase(Locale.ROOT).split("[^\\p{L}]+", 2)[0];
        return switch (first) {
            case "sim", "yes" -> Boolean.TRUE;
            case "nao", "não", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private void countError() {
        if (errors != null) {
            errors.increment();
        }
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        Message message = response.getResult().getOutput();
        return message == null ? null : message.getText();
    }
}
