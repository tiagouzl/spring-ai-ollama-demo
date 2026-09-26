# Repository Memory — index

Canonical durable knowledge for `spring-ai-ollama-demo`. Entry point: every
doc below carries frontmatter with `owned_paths` and `last_verified_commit`.

## Module cards

- [security](security.md) — API-key auth, rate limiting, CORS, GET-prompt
  guard, actuator protection; the `/ai/**` boundary and client namespaces.
- [chat-memory](chat-memory.md) — persistent conversation storage: window,
  TTL purge, namespace-derived conversation ids, deletion.

## Decisions

- [bulkhead-chat-model-decorator](bulkhead-chat-model-decorator.md) — why the
  LLM concurrency bulkhead is a decorator wired at the `ChatClient.Builder`
  and not a bean post-processor.

## Lessons

- [chat-memory-timestamp-quoting-differs-per-dialect](lessons/chat-memory-timestamp-quoting-differs-per-dialect.md)
- [maven-incremental-compile-masks-classpath-breaks](lessons/maven-incremental-compile-masks-classpath-breaks.md)
- [new-dashscope-autoconfig-classes-need-exclude-list-update](lessons/new-dashscope-autoconfig-classes-need-exclude-list-update.md)
- [plain-objectmapper-missing-jsr310-module](lessons/plain-objectmapper-missing-jsr310-module.md)
- [beanpostprocessor-injection-drops-jvm-meters](lessons/beanpostprocessor-injection-drops-jvm-meters.md)
- [llm-judge-must-be-measured-with-a-positive-control](lessons/llm-judge-must-be-measured-with-a-positive-control.md)
- [running-tests-while-the-compose-app-is-up-locks-hsqldb](lessons/running-tests-while-the-compose-app-is-up-locks-hsqldb.md)
- [guardrail-decorator-swallows-the-concrete-bean-type](lessons/guardrail-decorator-swallows-the-concrete-bean-type.md)
- [security-starter-form-login-chain-breaks-interceptor-auth](lessons/security-starter-form-login-chain-breaks-interceptor-auth.md)

## Reports

Cycle-by-cycle memory-update reports live in [reports/](reports/).

## Known gaps

- No contract docs yet (e.g. Alibaba/Ollama fallback contract, semantic-cache
  namespace contract) — candidates when those areas change.
- No runbooks (E2E Ollama/Redis/pgvector suite is pom-profile driven;
  documented in README instead).
