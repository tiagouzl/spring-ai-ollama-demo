# Memory Update Report — Semantic guardrail (LLM judge) + Boot 4 re-evaluation

## Summary
- Result: updated
- Source spec: none (bounded change — design approved in chat, no spec file)
- Source context: `docs/superpowers/memory/lessons/beanpostprocessor-injection-drops-jvm-meters.md` (the seam this feature extends)
- Source design: none
- Formal commits: `b6a0f80` (feature)
- Created docs: 0 (no new lesson — the two non-obvious rules landed as pitfalls in `security.md`, which is where a reader of that module looks first)
- Updated docs: 5 (security card, ANALISE §35, ANALISE §33 cross-ref, relatorio.md ×2, index unchanged)
- Deferred docs: 0

## Durable updates made
- Module cards:
  - `security.md` — new invariant for the semantic stage (opt-in, one extra model call, fail-open with `app.guardrails.semantic.errors`, meters only exist when enabled, judge built from the raw delegate); new pitfall: never give the judge the guarded model (recursion); `last_verified_commit` → `b6a0f80`.
- Contracts: `app.guardrails.semantic.{enabled,timeout,policies}` recorded in the card + ANALISE §35.
- Decisions: fail-open over fail-closed; question+answer+policies over answer-only; flag-gated so the default is unchanged; blocklist before judge — all in `ANALISE.md` §35.
- Runbooks: none.
- Lessons: none new — the BPP `ObjectProvider` rule already exists; the recursion rule is one line in the card's pitfalls.

## Not promoted
- The common-pool timeout implementation detail stays a `ponytail:` comment in the code, not memory — it is an implementation choice, not a rule for future agents.
- Spike methodology (how to check a blocker: upstream release pages + Maven Central) — generic, belongs nowhere in this repo's memory.

## Open gaps
- `ANALISE.md` §26's "Alternativas rejeitadas" already covered the milestone Alibaba; §35 now records the re-evaluation date and the trigger to revisit (Alibaba GA aligned with Spring AI 2 GA).
- Remaining P2 is now: a true guardrails layer (model-based judge is demo-grade and fallible in both directions) — documented, not silently dropped.
