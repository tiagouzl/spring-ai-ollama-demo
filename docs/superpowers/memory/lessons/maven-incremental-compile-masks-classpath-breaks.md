---
type: lesson
title: maven-incremental-compile-masks-classpath-breaks
summary: After a dependency version bump, `mvn compile` can succeed on stale classes and hide real API breaks until tests die with NoSuchMethodError — always `mvn clean`.
tags:
  - build
  - api
last_verified_commit: 5a76470d5fb19580c7ac72fbaccc2e7bf5fc05e0
status: active
---

## Situation

Upgrading Spring AI 1.0.1 → 1.1.8 in `pom.xml` (Boot parent, BOM imports),
then running `mvn -DskipTests compile` reported BUILD SUCCESS while the
sources still contained `MessageChatMemoryAdvisor.builder(chatMemory)
.conversationId(...)` — a method removed in 1.1.x. Maven's incremental
compiler only compares source timestamps to class timestamps; the classpath
change (1.0.1 → 1.1.8) does not invalidate `target/classes`. The break only
surfaced later as `NoSuchMethodError` at test runtime, which looks like a
classpath conflict rather than a stale-build artifact.

## Why It Mattered

A green "compile" after a version bump is a false signal: it delayed
detection of a real interface-contract change, and the misleading
`NoSuchMethodError` pointed the investigation at jar-version conflicts
instead of the actual root cause (stale class + removed method). Roughly an
hour went into ruling out dependency conflicts before `javap` on the 1.1.8
jar showed the method simply no longer exists.

## Rule

After every change to dependency versions in `pom.xml`, run `mvn clean …`
(clean compile / clean verify) — never trust an incremental compile across a
classpath change. If a test later fails with `NoSuchMethodError` /
`NoClassDefFoundError`, suspect stale `target/classes` first and re-run with
`clean` before hunting for jar conflicts; then confirm the real API delta
with `javap -cp <new-jar> <Class>` (or `javap …$Builder`).

## When to Apply

Any time a version property or parent/BOM in `pom.xml` changes while the
source files stay untouched — the exact conditions where the incremental
compiler skips recompilation. Also when a `NoSuchMethodError` appears
immediately after an upgrade despite a "successful" compile.

## When NOT to Apply

Pure docs/config/test-resource changes that do not alter the classpath; or
workflows where a `clean` build just ran as part of the same change (e.g.
CI always runs `clean verify` — the trap only exists in local incremental
loops).
