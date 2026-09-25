package com.example.ai.config;

import com.example.ai.security.OutputGuardrail;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

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

    private final ChatModel delegate;
    private final OutputGuardrail guardrail;

    public GuardedOutputChatModel(ChatModel delegate, OutputGuardrail guardrail) {
        this.delegate = delegate;
        this.guardrail = guardrail;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return redact(delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> delegate.stream(prompt).collectList())
                .map(GuardedOutputChatModel::merge)
                .map(this::redact)
                .flatMapIterable(r -> List.of(r));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private ChatResponse redact(ChatResponse response) {
        String text = textOf(response);
        if (text == null || !guardrail.isBlocked(text)) {
            return response;
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(OutputGuardrail.redactedText()))));
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
