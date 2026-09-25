# Memory Update Report — LLM bulkhead cycle

## Summary
- Result: no_memory_update (for `docs/superpowers/memory/`)
- Source spec: bounded design aprovado em chat; `ANALISE.md` §31
- Source context: `app.llm.max-concurrent` + `ConcurrentBulkheadChatModel`
- Source design: `none`
- Formal commits: `04bbc9f` (report written before commit authorization)
- Created docs: 1 (this report)
- Updated docs: 0
- Deferred docs: 0

## Durable updates made
- None under `docs/superpowers/memory/` — see below.

## Not promoted
- **Decorator placement** (wrap at the `@Primary ChatClient.Builder`, not via
  BeanPostProcessor — the latter would replace the `OllamaChatModel` bean type
  and break every `@MockitoBean OllamaChatModel` test) — recorded in
  `ANALISE.md` §31 where the next reader of that wiring will look.
- **`Flux.defer`/`doFinally` permit discipline for SSE bulkheads** — standard
  Reactor practice, documented in the class javadoc at the point of use.
- **Semaphore-would-break-mocks insight** — same as the decorator placement,
  single decision folded into §31.

## Open gaps
- Unchanged: no `index.md` (5 lessons); memory tree still `lessons/` + `reports/`
  only — no module cards/contracts/decisions backfilled from cycles 1–3.
