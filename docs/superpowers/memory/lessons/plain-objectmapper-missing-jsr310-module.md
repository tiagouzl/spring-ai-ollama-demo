---
type: lesson
title: plain-objectmapper-missing-jsr310-module
summary: Unit tests that serialise with a bare `new ObjectMapper()` fail on `Instant` fields — Boot's auto-config registers JavaTimeModule, your test does not.
tags:
  - testing
last_verified_commit: 3f41bf9bc1a31251191d8de8991617cc433c0819
status: active
---

## Situation

Writing `RateLimitConcurrencyTest` (no Spring context, per the repo's "Unit (no
Spring, no Docker)" convention), the interceptor's reject path serialises an
`ApiError` whose `timestamp` is a `java.time.Instant`. The test built
`new ObjectMapper()` directly, so the call failed inside a worker thread with
`InvalidDefinitionException: Java 8 date/time type Instant not supported` —
wrapped in `ExecutionException`, which obscured where it came from. The same
app works because Boot's Jackson auto-configuration registers
`jackson-datatype-jsr310` for every auto-configured mapper.

## Why It Mattered

The failure surfaced far from its cause (a `Future.get()` wrapper in an
assertion loop), costing a full test round-trip to diagnose. Every future
plain-unit test touching `ApiError`, `Instant`, or `LocalDateTime` on a
serialisation path would hit the identical wall.

## Rule

When a test constructs an `ObjectMapper` for a component that the Spring Boot
context would normally wire, register `new JavaTimeModule()` (or build via
`JsonMapper.builder().addModule(new JavaTimeModule())`) — never assume a bare
mapper behaves like Boot's.

## When to Apply

Plain (no-`@SpringBootTest`) unit tests in this repo that exercise error
writing (`ApiErrorWriter`, `RateLimitInterceptor.reject`), caching payloads,
or anything else serialised at runtime by the context's mapper.

## When NOT to Apply

Tests running under `@SpringBootTest`/MockMvc — they get Boot's configured
mapper for free. Also skip when the object graph contains no `java.time`
types; a bare mapper serialises POJOs/Strings fine.
