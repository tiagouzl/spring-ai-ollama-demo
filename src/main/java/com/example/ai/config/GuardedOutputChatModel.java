package com.example.ai.config;

import com.example.ai.security.EmbeddingPolicyClassifier;
import com.example.ai.security.OutputGuardrail;
import com.example.ai.security.SemanticGuardrailJudge;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * Wraps a {@link ChatModel} and redacts answers that trip the
 * {@link OutputGuardrail}. Placed on every model bean (see
 * {@code ChatModelGuardrailBeanPostProcessor}), so all callers — chat, RAG,
 * tools, structured output — are covered by one seam instead of per call site.
 * <p>
 * Streaming is aggregated and re-emitted as a single response: a guardrail that
 * lets the violating chunk through first is no guardrail, and the SSE endpoint
 * still works, just without incremental chunks.
 * </p>
 * <p>
 * Clean answers are returned byte-for-byte and the blocked text never leaves
 * this class. Textual content only — tool-call arguments are out of scope.
 * </p>
 */
public class GuardedOutputChatModel implements ChatModel {

    /** Same stable prefix as the blocklist redaction — one contract for clients. */
    private static final String SEMANTIC_REDACTION =
            OutputGuardrail.REDACTED_PREFIX
                    + " Response blocked by the semantic guardrail (app.guardrails.semantic.policies).";

    private static final String EMBEDDING_REDACTION =
            OutputGuardrail.REDACTED_PREFIX
                    + " Response blocked by the embedding guardrail (app.guardrails.embeddings).";

    private final ChatModel delegate;
    private final OutputGuardrail guardrail;
    private final SemanticGuardrailJudge semanticJudge;
    private final EmbeddingPolicyClassifier embeddingClassifier;

    public GuardedOutputChatModel(ChatModel delegate, OutputGuardrail guardrail) {
        this(delegate, guardrail, null, null);
    }

    public GuardedOutputChatModel(ChatModel delegate, OutputGuardrail guardrail,
                                  SemanticGuardrailJudge semanticJudge) {
        this(delegate, guardrail, semanticJudge, null);
    }

    public GuardedOutputChatModel(ChatModel delegate, OutputGuardrail guardrail,
                                  SemanticGuardrailJudge semanticJudge,
                                  EmbeddingPolicyClassifier embeddingClassifier) {
        this.delegate = delegate;
        this.guardrail = guardrail;
        // The judge shares this model's raw delegate: giving it the guarded
        // model would make every verdict re-enter the guardrail.
        this.semanticJudge = semanticJudge != null
                ? semanticJudge
                : new SemanticGuardrailJudge(delegate, List.of(), Duration.ZERO, false, null);
        this.embeddingClassifier = embeddingClassifier;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return redact(delegate.call(prompt), questionOf(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String question = questionOf(prompt);
        return Flux.defer(() -> delegate.stream(prompt).collectList())
                .map(GuardedOutputChatModel::merge)
                .map(response -> redact(response, question))
                .flatMapIterable(r -> List.of(r));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private ChatResponse redact(ChatResponse response, String question) {
        String text = textOf(response);
        if (text == null) {
            return response;
        }
        if (guardrail.isBlocked(text)) {
            return redacted(OutputGuardrail.redactedText());
        }
        // Blocklist first: free and deterministic. Then the embedding stage:
        // also deterministic, one cheap call, and — unlike the judge — proven
        // by a calibrated threshold. The judge only sees what both miss.
        if (embeddingClassifier != null && embeddingClassifier.isViolation(text)) {
            return redacted(EMBEDDING_REDACTION);
        }
        if (semanticJudge.isViolation(question, text)) {
            return redacted(SEMANTIC_REDACTION);
        }
        return response;
    }

    private static ChatResponse redacted(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** The user's turn — the last instruction, which is the question being answered. */
    private static String questionOf(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null || prompt.getInstructions().isEmpty()) {
            return "";
        }
        Message last = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
        return last.getText() == null ? "" : last.getText();
    }

    private static ChatResponse merge(List<ChatResponse> responses) {
        if (responses.size() == 1) {
            return responses.get(0);
        }
        String text = responses.stream()
                .map(GuardedOutputChatModel::textOf)
                .filter(t -> t != null)
                .reduce("", (a, b) -> a + b);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static String textOf(ChatResponse response) {
        return response == null ? null : response.getResult().getOutput().getText();
    }
}
