# Memory Update Report — Output guardrail

## Summary
- Result: updated
- Source spec: none (bounded change — design approved in chat, no spec file)
- Source context: `docs/superpowers/plans/2026-09-25-oidc-jwt-resource-server.md` (preceding cycle; security boundaries)
- Source design: none
- Formal commits: `15e8740` (feature)
- Created docs: 1 (lesson)
- Updated docs: 4 (index, security card, ANALISE §34, relatorio.md)
- Deferred docs: 0

## Durable updates made
- Module cards:
  - `security.md` — added the output-guardrail responsibility; new invariant (redacted 200 + `[output-guardrail]` prefix + `app.security.outputguardrail.triggered`, single-event streaming, textual-only); `PromptGuard` line now says per call site (drifts) vs the output seam (cannot drift); pitfall: keep `ObjectProvider` in the BPP; `last_verified_commit` → `15e8740`.
- Contracts: none new beyond the invariant above (config name and redaction contract recorded in the card + ANALISE §34).
- Decisions: redacted-200 over error status, model seam over per-call-site, aggregated streaming — all recorded in `ANALISE.md` §34.
- Runbooks: none.
- Lessons:
  - NEW `lessons/beanpostprocessor-injection-drops-jvm-meters.md` — a BPP injecting regular beans creates the `MeterRegistry` before its binders; JVM meters vanish from `/actuator/prometheus`. Only the observability test caught it. Anchored `15e8740`.
  - `index.md` — lesson entry added.

## Not promoted
- `@MockitoBean` bypasses bean post-processors — discovered while testing, but it is a framework fact any Spring developer knows; the wiring-test design (`OutputGuardrailWiringTest`) carries the practical consequence.
- The two in-cycle defects (empty-list fallback, BPP early init) are fixed and anchored in the lesson; no separate doc.

## Open gaps
- Real guardrails layer (semantic/modelled) remains open P2 — recorded in `security.md` pitfalls and `relatorio.md`.
