---
type: lesson
title: guardrail-decorator-swallows-the-concrete-bean-type
summary: A BeanPostProcessor that wraps a bean changes its concrete type, so type-based injection of the original class silently fails at runtime — and @MockitoBean hides it, because mocks bypass the post-processor.
tags:
  - build
  - auth
last_verified_commit: 1b69ba4b050093be7d1d87c3fe3ae5a919b82cc3
status: active
---

## Situation

The output-guardrail `ChatModelGuardrailBeanPostProcessor` wraps every `ChatModel` bean in a `GuardedOutputChatModel`. `AlibabaChatController` injects `ObjectProvider<DashScopeChatModel>` by type. Because the decorator implements only `ChatModel`, the `dashScopeChatModel` bean arrives as a `GuardedOutputChatModel`, and the by-type `ObjectProvider<DashScopeChatModel>` no longer matches it: with a real (non-mocked) DashScope auto-configured, context startup fails with `BeanNotOfRequiredTypeException` — bean `dashScopeChatModel` expected `DashScopeChatModel` but was `GuardedOutputChatModel`. Nothing caught it: `AlibabaEnabledTest` mocks `DashScopeChatModel` with `@MockitoBean`, and Mockito replaces the bean outright, bypassing the post-processor — the exact mechanism that lets the bug live. It surfaced only when a test used the real auto-configured bean.

## Why It Mattered

This is a production break, not a test artifact: any deployment with `DASHSCOPE_API_KEY` set would have failed to start. The guardrail — whose entire purpose is to be inescapable — made one path unreachable. The seam's value (no caller can forget the guardrail) is paid for with the bean's identity, and nothing in the design said so.

## Rule

A `BeanPostProcessor` that substitutes a bean **breaks by-type injection of the original class**. Any consumer of a decorated bean must inject it as the *interface* it still satisfies, selected by `@Qualifier("<beanName>")`, not by its concrete type. When a decorator wraps a bean, audit every injection point for that bean by type and change it to interface + qualifier. And prove coverage with a real (un-mocked) bean — `@MockitoBean` bypasses post-processing and will report a green suite while the production wiring is broken.

## When to Apply

Any bean post-processor, AOP proxy, or decorator that returns a different object than the bean it wraps — interface-typed `ObjectProvider<T>`/`Object<T>` injections, `@Qualifier` by name, and auto-configured third-party beans are the usual casualties. Applies equally to security wrappers, metrics decorators, and retry/circuit-breaker proxies.

## When NOT to Apply

When the decorator is created inside a `@Bean` method (as `ConcurrentBulkheadChatModel` is, inside `chatClientBuilder`) the bean is never re-registered under a different type, so by-type injection is unaffected. And `@MockitoBean`-based tests are fine for anything that does not involve post-processing.
