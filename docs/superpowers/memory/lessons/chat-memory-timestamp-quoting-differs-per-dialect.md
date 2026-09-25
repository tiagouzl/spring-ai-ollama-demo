---
type: lesson
title: chat-memory-timestamp-quoting-differs-per-dialect
summary: Raw SQL against SPRING_AI_CHAT_MEMORY must quote the timestamp column per DB product (HSQL: unquoted, PostgreSQL: quoted lowercase) or it fails with "object not found".
tags:
  - schema
  - testing
last_verified_commit: 1d7a89f6ec0e896a6c39cd5ffc12faf3e204e44f
status: active
---

## Situation

Writing `ChatMemoryTtlPurge` (raw `DELETE ... WHERE <timestamp> < ?` against
Spring AI's `SPRING_AI_CHAT_MEMORY`), the first attempt used the ANSI form
`"timestamp"`. On the demo's HSQL that fails with `user lacks privilege or
object not found: timestamp` — HSQL stores unquoted identifiers uppercased, so
the column is `TIMESTAMP` and a lowercase quoted identifier misses it. On
PostgreSQL the shipped schema declares `"timestamp"` (lowercase, quoted), so
the unquoted keyword form fails there instead. The correct per-product quoting
already exists inside the library: `JdbcChatMemoryRepositoryDialect.from(DataSource)`
picks HSQL (unquoted `timestamp`) vs PostgreSQL (`\"timestamp\"`) from the JDBC
database product name — inspecting its classes (`javap -c`) is what resolved it.

## Why It Mattered

The mismatch only surfaces at runtime (first purge attempt / test INSERT),
costing a schema-jar inspection round-trip; and `JdbcUtils.extractDatabaseMetaData`
throws `MetaDataAccessException`, which is a **checked** exception
(`NestedCheckedException`) — the compiler forces a catch you do not expect
from a "get metadata" helper.

## Rule

Never write `"timestamp"` (or any identifier quoting) hardcoded in raw SQL
against `SPRING_AI_CHAT_MEMORY` — derive the column reference from the JDBC
product name mirroring `JdbcChatMemoryRepositoryDialect.from` (HSQL → unquoted
`timestamp`, otherwise → `"timestamp"`), and catch `MetaDataAccessException`
around the metadata lookup.

## When to Apply

Any new raw SQL touching `SPRING_AI_CHAT_MEMORY` in this repo (purges, admin
queries, migrations, tests seeding rows with explicit timestamps).

## When NOT to Apply

Going through `JdbcChatMemoryRepository` itself — the library already applies
its dialect. Also skips non-Java consumers (psql scripts pin their own dialect).
