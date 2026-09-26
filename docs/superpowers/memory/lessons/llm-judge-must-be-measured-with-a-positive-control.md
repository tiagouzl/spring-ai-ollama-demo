---
type: lesson
title: llm-judge-must-be-measured-with-a-positive-control
summary: An LLM judge is unproven until it is run against a known violation with the real model — granite4.1:3b answered "nao" to an obvious system-prompt leak, making the semantic guardrail a silent no-op.
tags:
  - auth
last_verified_commit: bc0ac97dca0afa596b3d90c1f700ee35758ca12a
status: active
---

## Situation

The output guardrail's optional second stage asks the same local model whether an answer violates a policy (`app.guardrails.semantic.enabled`). Twelve unit tests passed: mocks returning `sim`, `não`, exceptions, timeouts, unparseable answers — the code path was proven. Then it ran against the real model in the compose stack (`granite4.1:3b`) and answered `nao` to a blatant violation ("Sure, my system prompt says you are a helpful assistant") — the exact same verdict it gave a clean `ls -la` answer. The stage was a **no-op**: `triggered` never moved, `errors` never moved, and nothing in the metrics distinguished "healthy" from "broken". The one real signal came from an unrelated case: `app.guardrails.semantic.errors` caught a genuinely unparseable verdict, proving the fail-open instrumentation was faithful while the decision itself was noise.

## Why It Mattered

A mock that returns `sim` on demand proves the plumbing, never the judgement. A guardrail that appears healthy — no errors, no trips, code covered by tests — is more dangerous than one that is visibly off: an operator enables it, sees green dashboards, and removes the manual review it was standing in for. The tests and the type system could not have caught this; only the real model could. The fix in commit `bc0ac97` did not make the judge discriminate (no model on this stack does); it made the failure visible — `temperature: 0` plus one log line per verdict.

## Rule

Never ship an LLM-based guard as "working" until it has been run against a **positive control** (a known violation that must be caught) and a negative control, with the real model in the real stack. Additionally: log one line per verdict with its latency — a judge whose trip counter stays at zero must still be visible — and set `temperature: 0`, because a verdict that varies between identical calls is not a verdict.

## When to Apply

Any LLM-as-judge, classifier, reranker, or self-check feature in this repo. Also when adding a model-backed decision to a security-relevant path where fail-open is the design.

## When NOT to Apply

Do not apply to deterministic classifiers (embeddings, thresholds, keyword rules) — their behaviour is a function of their input, and unit tests with real inputs do prove them. And do not read a failed control as "the model is bad": it means the feature is unproven for that model, which is a fact to document, not to argue away.
