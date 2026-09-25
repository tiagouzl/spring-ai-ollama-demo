---
type: decision
title: bulkhead-chat-model-decorator
summary: The LLM concurrency bulkhead is a plain ChatModel decorator wired at the single ChatClient.Builder, not a BeanPostProcessor wrapping the bean.
tags:
  - concurrency
  - config
owned_paths:
  - src/main/java/com/example/ai/config/ConcurrentBulkheadChatModel.java
  - src/main/java/com/example/ai/config/PrimaryChatClientConfig.java
related_docs:
  - docs/superpowers/memory/index.md
  - ANALISE.md
entrypoints:
  - src/main/java/com/example/ai/config/PrimaryChatClientConfig.java
last_verified_commit: fb35dd41688c884c80db0dd079db6a6d55320be8
status: accepted
---

## Context

Cap concurrent Ollama calls so a burst fails fast with 429 instead of
queueing unboundedly. All chat routes (chat, memory, tools, streaming,
RAG, Alibaba fallback) flow through the `@Primary ChatClient.Builder`, which
is built from the `ollamaChatModel` bean. Controller tests mock that bean
with `@MockitoBean OllamaChatModel`.

## Decision

Ship `ConcurrentBulkheadChatModel implements ChatModel` (fair semaphore;
`call` acquires in try/finally, `stream` acquires in `Flux.defer` and
releases in `doFinally`; `<=0` passes through) and wire it **only** at
`PrimaryChatClientConfig.chatClientBuilder(@Qualifier("ollamaChatModel"))`,
switched by `app.llm.max-concurrent` (default 4). `LlmBulkheadFullException`
maps to 429 in `GlobalExceptionHandler`.

## Alternatives considered

- **BeanPostProcessor wrapping the bean** — rejected: `@MockitoBean` replaces the bean post-wiring, so either tests lose the decorator or the wrapper interferes with the mock; proven fragile in this suite.
- **Servlet filter / concurrency limit at HTTP layer** — rejected: does not cover internal call chains (memory advisors, Alibaba fallback, streaming) that already bypass HTTP concurrency.

## Trade-offs

- The decorator is invisible outside one wiring site; a future second
  `ChatClient.Builder` must wire it too (one line, same qualifier pattern).
- Semaphore bounds concurrency per decorator instance, not global across
  instances — fine for the demo's single-node deployment.

## Revisit signals

- A second chat model or builder appears — wire the decorator again or promote it to a wrapping `@Bean` with an explicit test seam.
- Multi-instance deployments need a global concurrency cap (out of scope; see security card's rate-limit pitfall).

Commit: `04bbc9f`.
