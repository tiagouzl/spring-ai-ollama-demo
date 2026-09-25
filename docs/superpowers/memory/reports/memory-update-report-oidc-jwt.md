# Memory Update Report — OIDC/JWT resource server

## Summary
- Result: updated
- Source spec: `docs/superpowers/specs/2026-09-25-oidc-jwt-resource-server-design.md` (v4, commit `a1d58d8`)
- Source context: `docs/superpowers/plans/2026-09-25-oidc-jwt-resource-server.md` (commit `ba70a41`)
- Source design: `none` (spec carries design)
- Formal commits: `a1d58d8..4abb614` (15 commits; feature range `d2289cd..62a7baa` + fix wave `4abb614`)
- Created docs: 1 (lesson)
- Updated docs: 3 (index, security card, ANALISE/relatorio already done in Task 12 `0ac7537`/`62a7baa`)
- Deferred docs: 1 (runbook — README's OIDC curl flow is the operational runbook; a memory duplicate would rot)

## Durable updates made
- Module cards:
  - `security.md` — added `OidcSecurityConfig` entrypoint; corrected `ClientIdentity.namespaceFor` description (JWT → key → IP precedence); extension point now names chain-level rules; `last_verified_commit` → `4abb614` (whole-feature CI green).
- Contracts: none new — the namespace formula and three-chain order were already captured as security.md invariants by Task 12 (`0ac7537`).
- Decisions: none new — principal-identity decisions (client_credentials service account, bearer-only, no roles, mutual exclusion) live in spec v4, which is the durable record.
- Runbooks: none — README §OIDC flow (Task 10) is the runbook.
- Lessons:
  - NEW `lessons/security-starter-form-login-chain-breaks-interceptor-auth.md` — adding `spring-boot-starter-oauth2-resource-server` installed a form-login chain that broke 46 tests; fix is the five-exclusions + single `@EnableWebSecurity` coupling; anchored `4abb614`.
  - `index.md` — added the lesson entry.

## Not promoted
- 11 deferred task-review minors — final whole-branch review triaged each (fix-before-merge items were fixed in `4abb614`; the rest explicitly ruled "leave"); no memory value.
- SDD ledger (briefs/reports/review packages) — workspace scratch, deleted after rulings collected; git history is the record.
- Compose "Keycloak placement" and "default-mode compose check" observations — resolved by `4abb614` pure-move and implicit test coverage.

## Open gaps
- None. Remaining P2 after this cycle: dedicated guardrails; Boot 4 / Spring AI 2 (blocked on Alibaba upstream) — both already tracked in `relatorio.md`/`ANALISE.md`.
