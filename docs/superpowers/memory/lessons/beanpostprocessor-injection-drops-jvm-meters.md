---
type: lesson
title: beanpostprocessor-injection-drops-jvm-meters
summary: A BeanPostProcessor that injects regular beans forces the MeterRegistry into existence during the post-processor phase, silently dropping JVM meters from the Prometheus scrape — use ObjectProvider.
tags:
  - build
  - infra
last_verified_commit: 15e87402ca9db5a8abbf4c272bb8770a74fc2c27
status: active
---

## Situation

Adding `ChatModelGuardrailBeanPostProcessor` (wraps every `ChatModel` bean) to the output-guardrail feature (commit `15e8740`) meant it injected `OutputGuardrail`, which injects `MeterRegistry`. The whole app stayed green except one assertion: `ObservabilityTest.prometheusEndpointExposesHttpMetrics` failed on `jvm_memory_used_bytes` missing from the scrape. Root cause: a `BeanPostProcessor` with constructor-injected regular beans is instantiated during the post-processor phase, *before* other post-processors (notably the one that applies `MeterBinder`s to `MeterRegistry`) are registered. The registry was therefore built with no JVM binders — a silent, production-visible telemetry loss that has nothing to do with the feature being built. Fix: inject `ObjectProvider<OutputGuardrail>` and call `getObject()` inside `postProcessAfterInitialization`.

## Why It Mattered

The failure is invisible in the feature's own tests — only a test that scrapes `/actuator/prometheus` catches it. Without the existing observability test, this would have shipped as "the app works, the guardrail works", with JVM metrics silently gone in production.

## Rule

Never inject regular beans into a `BeanPostProcessor` (or `BeanFactoryPostProcessor`) by constructor; take an `ObjectProvider` and resolve inside the callback. Any new `BeanPostProcessor` in this repo must be proven against the Prometheus scrape test.

## When to Apply

Any new `BeanPostProcessor`/`BeanFactoryPostProcessor`; any change that pulls `MeterRegistry` (or another early-initialized infrastructure bean) into a post-processor; any feature that adds beans consumed by framework infrastructure.

## When NOT to Apply

If the post-processor only touches `BeanDefinition`s (`BeanDefinitionRegistryPostProcessor`) or uses `@Value` on primitive config, constructor injection is safe. Do not apply the fix to code that deliberately needs eager initialization.
