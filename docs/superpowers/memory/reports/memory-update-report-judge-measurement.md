# Memory Update Report — Semantic judge measured against the real model

## Summary
- Result: updated
- Source spec: none (bounded change + measurement spike)
- Source context: `docs/superpowers/memory/lessons/llm-judge-must-be-measured-with-a-positive-control.md` (new)
- Source design: none
- Formal commits: `b6a0f80` (the semantic guardrail the measurement is about)
- Created docs: 1 (lesson)
- Updated docs: 4 (index, security card, ANALISE §35 measurement subsection, relatorio.md)
- Deferred docs: 0

## Durable updates made
- Module cards:
  - `security.md` — semantic invariant now records `temperature: 0`, the per-verdict INFO log, the ~21s cost, and the measured fact that `granite4.1:3b` judges everything `nao` (no-op), with the log line named as the only thing making that visible.
- Contracts: unchanged (`app.guardrails.semantic.*`); the judge call is now deterministic by construction.
- Decisions: keep the flag off by default — the feature is unproven for the default model, and "unproven" is a fact to record, not to argue away (ANALISE §35 measurement subsection; revisit trigger stated).
- Runbooks: none.
- Lessons:
  - NEW `lessons/llm-judge-must-be-measured-with-a-positive-control.md` — mocks prove the plumbing, never the judgement; run the positive and negative control with the real model, log one line per verdict, temperature 0. Anchored `b6a0f80`.

## Not promoted
- The `~/.unlazy` approval mechanics and the spike's docker/override recipe — process, not project knowledge.
- The Compose port/curl workarounds used to reach the model from the host — local trivia.

## Open gaps
- The semantic stage has no proven-working configuration on this stack. Two named paths: a model that discriminates (verify with the positive control first) or an embedding-based classifier (new design, needs labelled examples and a threshold). Tracked in `ANALISE.md` §35 and `relatorio.md` §5.
