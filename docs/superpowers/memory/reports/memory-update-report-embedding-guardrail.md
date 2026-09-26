# Memory Update Report — Deterministic embedding guardrail

## Summary
- Result: updated
- Source spec: none (bounded change, design approved in chat)
- Source context: `lessons/llm-judge-must-be-measured-with-a-positive-control.md` (the rule this stage exists to satisfy)
- Formal commits: `a72bccb`
- Created docs: 1 (lesson)
- Updated docs: 4 (index, security card, ANALISE §36, relatorio.md)
- Deferred docs: 0

## Durable updates made
- Module cards:
  - `security.md` — new invariant for the embedding stage: config, the calibrated threshold with its measured ranges, the opt-in gate, fail-open, the stage order (blocklist → embeddings → judge), and the runnable re-check command.
- Contracts: `app.guardrails.embeddings.{enabled,threshold}` + `guardrail/policy-examples.json` (the tuning surface: edit examples, not code).
- Decisions: default off; examples-over-policy-text; blocklist before embeddings before judge — all in `ANALISE.md` §36, including why Bonsai was dropped (7 GB RAM, CPU-only) and the measured separation.
- Runbooks: the calibration command is documented in the IT javadoc and ANALISE §36.
- Lessons:
  - NEW `lessons/running-tests-while-the-compose-app-is-up-locks-hsqldb.md` — the stack holds the HSQLDB chat-memory file the test JVM shares; failures name a missing driver and survive a code revert, so check `docker compose ps` and `lsof data/chat-memory*` first. Anchored `a72bccb`.

## Not promoted
- The calibration scratch script (embedded answers + cosine maths) — it lived in `/tmp` and the durable form is the IT; a repo copy would be a second thing to keep in sync.
- The exact per-control cosine values — summarised as ranges in the card; the IT recomputes them.

## Open gaps
- The separation between violation and benign answers is narrow (≈0.03). Documented, not solved: more labelled examples per policy is the tuning path, and a production deployment would want a dedicated guardrails service rather than any of the three stages.
