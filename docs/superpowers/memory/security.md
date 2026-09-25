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
  - src/main/java/com/example/ai/security/OidcSecurityConfig.java
  - src/main/java/com/example/ai/security/PromptGetGuardFilter.java
  - src/main/java/com/example/ai/security/ClientIdentity.java
last_verified_commit: 15e87402ca9db5a8abbf4c272bb8770a74fc2c27
status: active
---

## Responsibilities

- **API-key auth** (`ApiKeyAuthInterceptor`): comma-separated key list, constant-time compare, keys never logged — only SHA-256 fingerprints.
- **Rate limiting** (`RateLimitInterceptor`): per client+endpoint buckets, memory or Redis store.
- **CORS** (`CorsConfig`): deny-by-default (empty `app.cors.allowed-origins`), dev `*`, prod reads env.
- **GET-prompt guard** (`PromptGetGuardFilter`): 405 on prompt-carrying GETs under `/ai/**` when `app.post-only-prompts` (true only in prod profile).
- **Prompt heuristics** (`PromptGuard`): size cap + injection blocklist on input, applied per call site in the chat/RAG controllers.
- **Actuator protection** (`ActuatorApiKeyFilter`, `ProdAuthGuard`): metrics/prometheus behind the same key when configured; prod fails fast if keys missing.
- **Output guardrail** (`OutputGuardrail` + `GuardedOutputChatModel` + `ChatModelGuardrailBeanPostProcessor`): the model-layer seam that redacts blocked replies on *every* `ChatModel` bean — one seam instead of the input guard's 8 call sites.

## Entry points

- `ApiSecurityConfig.java:31-33` — registers key auth (order 0) and rate limit (order 1) on `/ai/**` only.
- `OidcSecurityConfig.java` — the app's only `@EnableWebSecurity` class: validating `JwtDecoder` plus the three ordered chains (all gated `app.oidc.enabled=true` except the `@Order 3` fallback, which is always registered to keep default mode chain-identical).
- `ClientIdentity.namespaceFor(request)` — JWT fingerprint (`jwt:issuer:azp|-:sub`) when a `JwtAuthenticationToken` is in the security context, else API-key fingerprint, else IP fingerprint; feeds rate-limit buckets, semantic-cache namespaces and conversation ids (`ClientIdentity.conversationId`).

## Invariants

- Everything under `/ai/**` is key-checked and rate-limited when keys are configured; actuator is covered by a separate filter.
- A client namespace is stable per key (or per IP when anonymous) — a session id from one namespace never aliases another's memory or cache entries.
- CORS never reflects origins unless explicitly configured.
- **OIDC mode** (`app.oidc.enabled=true`): three ordered chains
  (`@Order 1` `/ai/**` → `2` metrics/prometheus → `3` fallback `permitAll`,
  CSRF off, stateless); namespace = `jwt:issuer:azp|-:sub`; API key and JWT
  are mutually exclusive credentials;   `ActuatorApiKeyFilter` is not registered;
  `ProdAuthGuard` accepts `OIDC_ISSUER_URI` instead of a key.
- **Output guardrail** (`app.prompt-guard.output-blocked-phrases`, default = input list, empty = off): a trip never fails the request — the answer is replaced with the stable `"[output-guardrail] …"` text (200) and counted on `app.security.outputguardrail.triggered`; streaming is aggregated and re-emitted as a single event so no violating text can leak. Textual content only; a fixed blocklist is a first line of defence, not a guardrails layer.

## Extension points

- New endpoints under `/ai/**` get auth+rate-limit automatically; new security rules go into `ApiSecurityConfig` or a `OncePerRequestFilter` registered in `SecurityFilterConfig`; chain-level (HTTP) rules go into `OidcSecurityConfig` — keep `@EnableWebSecurity` there alone (see its class comment and the security-starter lesson).

## Common pitfalls

- `/v3/api-docs` and `/swagger-ui` are NOT behind the `/ai/**` guard — protect or disable them in production (confirmed via `ApiSecurityConfig.java:32`).
- Rate limiting fails open per request when Redis is down (by design, accepted finding) and does not coordinate across instances in the memory store.
- `PromptGuard` (input) and `OutputGuardrail` (output) are both heuristic — accepted demo-grade bypasses exist; a real guardrails layer is still open P2. The input guard is applied per call site (drifts); the output guardrail is applied at the model seam (cannot drift).
- `ChatModelGuardrailBeanPostProcessor` must keep resolving its guardrail through `ObjectProvider` — constructor injection creates the `MeterRegistry` before its binders and silently drops JVM meters from the Prometheus scrape (see the lesson doc).
