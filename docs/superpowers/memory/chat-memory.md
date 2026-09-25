---
type: module_card
title: chat-memory
summary: How conversations are stored, namespaced, purged and deleted — the invariants behind /ai/chat/memory.
tags:
  - memory
  - persistence
owned_paths:
  - src/main/java/com/example/ai/chat/MemoryChatController.java
  - src/main/java/com/example/ai/config/ChatMemoryConfig.java
  - src/main/java/com/example/ai/config/ChatMemoryTtlPurge.java
related_docs:
  - docs/superpowers/memory/lessons/chat-memory-timestamp-quoting-differs-per-dialect.md
  - docs/superpowers/memory/index.md
entrypoints:
  - src/main/java/com/example/ai/chat/MemoryChatController.java
last_verified_commit: fb35dd41688c884c80db0dd079db6a6d55320be8
status: active
---

## Responsibilities

- Multi-turn memory over `JdbcChatMemoryRepository` (HSQLDB file in dev, PostgreSQL in prod), 20-message window.
- `MessageChatMemoryAdvisor` wiring per request with `ChatMemory.CONVERSATION_ID`.
- Hourly TTL sweep (`ChatMemoryTtlPurge`) honouring `app.chat-memory.ttl-hours` (default 168, <=0 disables).
- Explicit conversation deletion.

## Entry points

- `GET /ai/session` — issue a session id.
- `GET|POST /ai/chat/memory` — chat with history (`requireSession`: non-blank, <=128 chars).
- `DELETE /ai/chat/memory/{sessionId}` — wipe the stored conversation (204, idempotent).

## Invariants

- `conversationId = ClientIdentity.conversationId(namespaceFor(request), sessionId)` on **every** path — chat and delete use the same derivation, so one client can never see or wipe another's memory.
- The TTL sweep and the delete endpoint both go through `ChatMemory.clear`/repository deletes; raw SQL must quote `timestamp` per product (HSQL unquoted, PostgreSQL quoted — see lesson).
- Prod profile requires env-backed datasource (`DATABASE_URL`) or fails fast at startup.

## Extension points

- Window size and TTL are properties (`spring.ai.chat.memory.*`, `app.chat-memory.ttl-hours`); tests run with `ttl-hours=0` in the test context to keep the scheduler and assertions deterministic.

## Common pitfalls

- Scheduler runs in every context unless disabled — tests asserting exact row counts must pin `app.chat-memory.ttl-hours=0` (set in `ChatMemoryTtlPurgeTest` context) or race the hourly sweep.
- Do not reuse GET for mutations: GET already means "chat via query param" and is 405-guarded in prod for prompt params.
