# Memory Update Report — index + module cards backfill

## Summary
- Result: durable docs created (backfill of cycles 1–6 of 25/09/2026)
- Source spec: user chose "fechar a lacuna de memória" (index + module cards)
- Source context: this session's commits `3f41bf9` `3dfb495` `1d7a89f` `04bbc9f` `9dcf92f` (+ memory commits `2ab41b9` `91b782b` `d626825` `0b75433` `fb35dd4`)
- Source design: docs/superpowers/memory doc types (module_card / decision / lesson)
- Formal commits: source cycle commits above; backfill itself awaiting commit authorization
- Created docs: 5 (index.md, security.md, chat-memory.md, bulkhead-chat-model-decorator.md, this report)
- Updated docs: 1 (ANALISE.md header — versions 3.5.16/1.1.8, 86 testes note)
- Deferred docs: 0

## Durable updates made
- `docs/superpowers/memory/index.md` — navigation entry point over lessons/cards/decisions + explicit gaps.
- `docs/superpowers/memory/security.md` (module card) — /ai/** boundary, client namespaces, swagger gap, fail-open rate limit.
- `docs/superpowers/memory/chat-memory.md` (module card) — window/TTL/namespace/delete invariants, ttl=0 test pinning.
- `docs/superpowers/memory/bulkhead-chat-model-decorator.md` (decision) — decorator at ChatClient.Builder vs BeanPostProcessor (commit `04bbc9f`).

## Not promoted
- POST-only prompt design, CORS policy, TTL dialect quoting as new docs — already covered by §29/§31, existing lesson and the two cards; duplication rejected by the quality bar.
- E2E Ollama/Redis/pgvector suite as a runbook — pom-profile driven and documented in README; no new procedure was created.

## Open gaps
- Unchanged: no contract docs (Alibaba/Ollama fallback, semantic-cache namespaces); no runbooks. 4 lessons + 2 module cards + 1 decision indexed; older pre-session cycles remain documented only in ANALISE.md sections.
