---
type: lesson
title: new-dashscope-autoconfig-classes-need-exclude-list-update
summary: Every spring-ai-alibaba bump can add provider-selection autoconfigs that eagerly demand DASHSCOPE_API_KEY (matchIfMissing=true) — diff the autoconfigure package against application.yml's exclude list or all @SpringBootTest contexts die.
tags:
  - build
  - api
last_verified_commit: 5a76470d5fb19580c7ac72fbaccc2e7bf5fc05e0
status: active
---

## Situation

Bumping `spring-ai-alibaba` 1.0.0.4 → 1.1.2.4-security-fix made every
`@SpringBootTest` fail at context load with `IllegalStateException: DashScope
API key must be set`, thrown by `DashScopeMultimodalEmbeddingAutoConfiguration`
— a class **new** in 1.1.x. The project deliberately keeps DashScope
optional (no key → app boots, `/alibaba/*` returns 503) and already excluded
8 DashScope autoconfig classes in `spring.autoconfigure.exclude`
(`application.yml`), but nothing diffed the upstream autoconfigure package
for new classes during the upgrade. Alibaba's provider-selection properties
(`spring.ai.model.embedding.multimodal`, `spring.ai.model.chat`, …) default
to `dashscope` with `matchIfMissing=true`, so the new class activated without
any configuration and validated the key eagerly at bean creation.

## Why It Mattered

The failure mode is global — no `@SpringBootTest` could load, so the whole
suite looked catastrophically broken rather than "one new autoconfig". The
root cause was invisible from the stack trace alone (it names the class but
not that the fix is a one-line exclude), and DashScope-optional is an
explicit product contract of this app (`AlibabaFallbackTest` asserts the
503 path).

## Rule

On every `spring-ai-alibaba` version bump, diff the classes under
`com/alibaba/cloud/ai/autoconfigure/dashscope/` in the new jar against the
`spring.autoconfigure.exclude` list in `application.yml`
(`unzip -l <jar> | grep AutoConfiguration`), and add every new class that
validates credentials eagerly — or set the matching `spring.ai.model.*`
selector property away from `dashscope` for capabilities this app doesn't
use. `@SpringBootTest` without `DASHSCOPE_API_KEY` is the tripwire that
proves the contract holds.

## When to Apply

Any upgrade of `spring-ai-alibaba` / DashScope starter in a repo that must
boot without `DASHSCOPE_API_KEY`. Signal: context-load failures naming a
`…AutoConfiguration` class from `com.alibaba.cloud.ai.autoconfigure`, or a
`DashScope API key must be set` / `IllegalStateException` at startup with no
key configured.

## When NOT to Apply

Apps that always supply `spring.ai.dashscope.api-key` (eager validation then
harmless); or when all provider-selection properties are already pinned
explicitly to another provider for every capability (chat, image, video,
audio, rerank, agent, embedding text/multimodal) — then no DashScope
autoconfig activates in the first place.
