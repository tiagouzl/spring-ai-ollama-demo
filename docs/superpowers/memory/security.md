---
type: module_card
title: security-boundary
summary: The /ai/** security layer — API-key auth, rate limiting, CORS, GET-prompt guard, actuator protection — and the client-namespace derivation everything else reuses.
tags:
  - security
  - api
owned_paths:
  - src/main/java/com/example/ai/security/
  - src/main/java/com/example/ai/config/CorsConfig.java
related_docs:
  - docs/superpowers/memory/index.md
  - ANALISE.md
entrypoints:
  - src/main/java/com/example/ai/security/ApiSecurityConfig.java
  - src/main/java/com/example/ai/security/PromptGetGuardFilter.java
  - src/main/java/com/example/ai/security/ClientIdentity.java
last_verified_commit: 0ac753777d23549cbfe024fe0ae3137200d3dd0f
status: active
---

## Responsibilities

- **API-key auth** (`ApiKeyAuthInterceptor`): comma-separated key list, constant-time compare, keys never logged — only SHA-256 fingerprints.
- **Rate limiting** (`RateLimitInterceptor`): per client+endpoint buckets, memory or Redis store.
- **CORS** (`CorsConfig`): deny-by-default (empty `app.cors.allowed-origins`), dev `*`, prod reads env.
- **GET-prompt guard** (`PromptGetGuardFilter`): 405 on prompt-carrying GETs under `/ai/**` when `app.post-only-prompts` (true only in prod profile).
- **Prompt heuristics** (`PromptGuard`): size cap + injection blocklist, called from chat controllers.
- **Actuator protection** (`ActuatorApiKeyFilter`, `ProdAuthGuard`): metrics/prometheus behind the same key when configured; prod fails fast if keys missing.

## Entry points

- `ApiSecurityConfig.java:31-33` — registers key auth (order 0) and rate limit (order 1) on `/ai/**` only.
- `ClientIdentity.namespaceFor(request)` — API-key fingerprint if authenticated, else IP fingerprint; feeds rate-limit buckets, semantic-cache namespaces and conversation ids (`ClientIdentity.conversationId`).

## Invariants

- Everything under `/ai/**` is key-checked and rate-limited when keys are configured; actuator is covered by a separate filter.
- A client namespace is stable per key (or per IP when anonymous) — a session id from one namespace never aliases another's memory or cache entries.
- CORS never reflects origins unless explicitly configured.
- **OIDC mode** (`app.oidc.enabled=true`): three ordered chains
  (`@Order 1` `/ai/**` → `2` metrics/prometheus → `3` fallback `permitAll`,
  CSRF off, stateless); namespace = `jwt:issuer:azp|-:sub`; API key and JWT
  are mutually exclusive credentials; `ActuatorApiKeyFilter` is not registered;
  `ProdAuthGuard` accepts `OIDC_ISSUER_URI` instead of a key.

## Extension points

- New endpoints under `/ai/**` get auth+rate-limit automatically; new security rules go into `ApiSecurityConfig` or a `OncePerRequestFilter` registered in `SecurityFilterConfig`.

## Common pitfalls

- `/v3/api-docs` and `/swagger-ui` are NOT behind the `/ai/**` guard — protect or disable them in production (confirmed via `ApiSecurityConfig.java:32`).
- Rate limiting fails open per request when Redis is down (by design, accepted finding) and does not coordinate across instances in the memory store.
- `PromptGuard` is heuristic — accepted demo-grade bypasses exist; dedicated output guardrails are still open P2.
