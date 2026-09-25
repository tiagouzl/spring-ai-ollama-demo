# Memory Update Report — chat-memory TTL cycle

## Summary
- Result: updated
- Source spec: bounded design aprovado em chat; `ANALISE.md` §30
- Source context: `app.chat-memory.ttl-hours` + `ChatMemoryTtlPurge`
- Source design: `none`
- Formal commits: `1d7a89f6ec0e896a6c39cd5ffc12faf3e204e44f`
- Created docs: 2 (1 lesson, 1 report)
- Updated docs: 0
- Deferred docs: 0

## Durable updates made
- Module cards: none (no boundary changes — new sibling component in `config/`)
- Contracts: none — TTL behaviour canonically in `README.md` (table + memory
  section) and `ANALISE.md` §30
- Decisions: none — product-name quoting decision lives in §30 and the lesson below
- Runbooks: none
- Lessons: `lessons/chat-memory-timestamp-quoting-differs-per-dialect.md`
  (raw SQL vs `SPRING_AI_CHAT_MEMORY`: HSQL unquoted `TIMESTAMP` vs PostgreSQL
  `"timestamp"`; `MetaDataAccessException` is checked)

## Not promoted
- **Scheduler-vs-test race avoidance** (test context runs `ttl-hours=0` so the
  real `@Scheduled` tick can't race assertions) — recorded in §30 and the test
  javadoc where the next reader lands; single-use pattern.
- **Checked `MetaDataAccessException`** — folded into the quoting lesson above,
  not a standalone doc.

## Open gaps
- Same as previous reports: no `index.md` (4 lessons now); memory tree still
  only `lessons/` + `reports/` — no module cards/contracts/decisions backfilled.
