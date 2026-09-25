# Memory Update Report — batch §3/§4 (CORS deny-by-default + test hardening)

## Summary
- Result: updated
- Source spec: `relatorio.md` §3 (achados em aberto) / §4 (validações); `ANALISE.md` §28
- Source context: cycle post-PR #12, bounded batch approved by user
- Source design: `none`
- Formal commits: `3f41bf9bc1a31251191d8de8991617cc433c0819`
- Created docs: 2 (1 lesson, 1 report)
- Updated docs: 0
- Deferred docs: 0

## Durable updates made
- Module cards: none (no boundary/entrance changes — `CorsConfig` behaviour only)
- Contracts: none — see "Not promoted"
- Decisions: none — see "Not promoted"
- Runbooks: none
- Lessons: `lessons/plain-objectmapper-missing-jsr310-module.md` (bare
  `ObjectMapper` in plain unit tests lacks `JavaTimeModule`; masked by
  `ExecutionException` in worker threads)

## Not promoted
- **CORS deny-by-default contract** — already canonically documented in
  `ANALISE.md` §28, `README.md` (table + profiles section) and the commit
  message; a memory contract doc would duplicate three sources.
- **"Assert bean contract, not concrete class" test pattern** — recorded in
  the `RagDefaultStoreTest` javadoc where the next reader lands; too generic
  for a standalone memory doc.
- **CORS wildcard→empty decision** — same as the contract above; trade-offs
  (dev `*`, prod fail-fast) live in §28.

## Open gaps
- Gap: `docs/superpowers/memory/index.md` still does not exist (`distilling-lessons`
  rule: do not create it just for a lesson entry; backfill belongs to a
  `bootstrapping-repository-memory` pass).
- Gap: memory tree contains only `lessons/` — no module cards, contracts,
  decisions or runbooks from cycles 1–3 were ever backfilled.
