---
type: lesson
title: running-tests-while-the-compose-app-is-up-locks-hsqldb
summary: The compose app holds the HSQLDB chat-memory file at ./data/chat-memory — a path the test JVM shares — so any Spring context test fails with "Failed to determine DatabaseDriver" until the stack is down.
tags:
  - build
  - infra
last_verified_commit: a72bccb73e4aea44b208f45188fa13da6bb199c8
status: active
---

## Situation

After the guardrail calibration spike left the compose stack running (needed for the embedding model), the next full `./mvnw verify` failed with 72 errors spread across every `@SpringBootTest` class — all reporting `ApplicationContext failure threshold exceeded`, rooted in `jdbcChatMemoryScriptDatabaseInitializer threw exception: Failed to determine DatabaseDriver`. Nothing in the diff was responsible: stashing the new code reproduced the failure, and killing an orphaned `mvn verify` that held `data/chat-memory.lck` did not fix it. `docker compose down` did. The app's `spring.datasource.url` is `jdbc:hsqldb:file:./data/chat-memory`, resolved relative to the working directory, and the container mounts `./data` — the same directory the test JVM writes to. HSQLDB is single-writer: the app owns the file and the test JVM gets no driver.

## Why It Mattered

The error names a missing database driver, so the instinct is to blame the datasource config or the code under change. The real cause — a running container from a spike — is invisible in the stack trace and survives a code revert, which sends you hunting in the wrong place. It also poisons the *next* run: an interrupted `mvn verify` leaves an orphan JVM holding `chat-memory.lck`, so the failure outlives the command that caused it.

## Rule

Run `./mvnw verify` with the compose stack **down**. If a Spring context test fails with `Failed to determine DatabaseDriver` (or a context threshold cascade), check `lsof data/chat-memory*` and `docker compose ps` before touching any code. If a build is interrupted, kill the orphan JVM and remove `data/chat-memory.lck`.

## When to Apply

Any test run in this repo that loads a Spring context, and any debugging of datasource/context-load failures. It does not apply to pure unit tests (no context, no datasource) or to tests run inside the compose network against the app's own datasource.

## Not to Apply

Do not "fix" it by pointing tests at an in-memory HSQLDB URL or by excluding the chat-memory auto-configuration — the shared `data/` directory is deliberate (chat memory must survive restarts), and moving it would change behaviour the product depends on.
