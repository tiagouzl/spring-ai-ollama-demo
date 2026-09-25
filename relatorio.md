# Relatório do Projeto — `spring-ai-ollama-demo`

**Data:** 25/09/2026
**Branch:** `chore/dependency-security-upgrade` (base: `8ddfc47`, `main`: `e327778`)
**Substitui:** `relatorio-cline.md` e `proximos-passos.md` (desatualizados)

Este documento consolida dois ciclos: o hardening auditado na branch (commit
`8ddfc47`) e a revisão + correções aplicadas em seguida, com o estado validado
mais recente.

## 1. Resumo executivo

- **Hardening `8ddfc47`**: isolamento por cliente, privacidade de logs, ordem
  auth→rate-limit, `SCAN` em vez de `KEYS`, `PromptGuard` em todos os fluxos,
  `Cache-Control: no-store`, Compose restrito a `127.0.0.1`.
- **Revisão posterior** (Standards + análise) apontou ~18 achados; foi aprovado
  um subset de alto valor, parcialmente aplicado (ver §3).
- **Validação atual:** `./mvnw verify` verde com **61 testes, 0 falhas**, gate
  JaCoCo aprovado com pisos novos (0.60/0.45 → **0.65/0.54**; atuais medidos:
  LINE 67.1%, BRANCH 56.3%).
- **Não concluído:** upgrade coordenado de dependências (nunca iniciado no POM),
  E2E pgvector, gate Trivy bloqueante.

## 2. Estado do repositório

Working tree **limpa**; commits do ciclo 2 criados:

| Commit | Mensagem |
|---|---|
| `7bcd5db` | docs: add project report and upgrade plan |
| `b3e5b42` | fix: per-endpoint rate limit key |
| `4938132` | test: lock semantic cache TTL eviction |
| `58d888f` | build: raise jacoco floor to 0.65/0.54 |

Branch pushado para `origin/chore/dependency-security-upgrade` (autorizado);
`origin/main` permanece em `e327778`; nenhum PR.

## 3. Ciclo 2 — Revisão e correções aplicadas

### Aplicado

1. **Rate limit por endpoint** — `RateLimitInterceptor.clientKey` passou a ser
   fingerprint + `request.getRequestURI()` (memória e Redis); mensagem 429
   actualizada para "per client and endpoint"; README alinhado.
   Teste TDD `RateLimitPerPathTest` (red→green): esgotar `/ai/chat` já não
   bloqueia `/ai/session`.
   **Trade-off operacional:** a quota deixou de ser global — o total potencial
   por cliente passa a ser `capacity × nº de endpoints` (antes: `capacity`).
2. **Teste de TTL do cache** — `SemanticCacheUnitTest.expiredEntriesAreNotServed`
   trava a evicção eager antes do matching. A correção de código proposta na
   análise **não era necessária**: `SemanticCache.lookup` já remove expirados
   antes do loop (o achado original estava errado).
3. **Piso JaCoCo** (`pom.xml`): LINE 0.60→0.65, BRANCH 0.45→0.54, com medições
   actuais documentadas no comentário.

### Aprovado para saltar (com razão)

- **Centralizar `PromptGuard`**: a lógica já é única (`PromptGuard.validate`);
  os 10 call sites são one-liners. Centralizar exigiria AOP novo ou Bean
  Validation nos DTOs — dependência/mágica para pouco ganho num repo de ensino.
- Splits estruturais (`SemanticCache`/`RateLimitInterceptor`), tipos de domínio,
  message chains, backends Redis duplicados (feature deliberada), CORS default,
  Trivy bloqueante (depende do upgrade).

### Achados em aberto (não incluídos no subset)

- CORS wildcard por omissão + API key opcional = API aberta por defeito.
- PromptGuard contornável (heurístico, aceitável em demo — defence in depth).
- Testes frágeis (`RagDefaultStoreTest` instanceof, `OpenApiDocsTest`).
- Sem testes de concorrência do rate limiter nem de frases custom do guard.
- Fail-open Redis em multi-instância multiplica a taxa efectiva.

## 4. Validações

| Validação | Quando | Resultado |
|---|---|---|
| `./mvnw verify` (61 testes, JaCoCo 0.65/0.54) | ciclo 2, agora | **BUILD SUCCESS** |
| Testes de rate limit (`RateLimit*`, `ApiKeyRateLimitIsolation`, `RedisFallback`) | ciclo 2 | 5/5 verdes |
| `SemanticCacheUnitTest` (7, incl. TTL) | ciclo 2 | verde |
| `./mvnw verify` (59 testes) + JaCoCo | ciclo 1 (`8ddfc47`) | verde |
| E2E Redis (`SemanticCacheRedisE2EIT`, `RedisRateLimitE2EIT`) | ciclo 1 | verde |
| `docker compose config`, `git diff --check`, `graft build` | ciclo 1 | verde |
| E2E pgvector (`E2E_PG=true … PgVectorE2EIT`) | — | **pendente** |

## 5. Riscos residuais

- **Dependências**: advisories HIGH/CRITICAL nos pins actuais (Boot 3.4.5 /
  Spring AI 1.0.1 / Alibaba 1.0.0.4 / driver PostgreSQL transitivo); upgrade
  deve ser coordenado — Alibaba 1.0.0.4 foi construído sobre Spring AI 1.0.1.
- **Trivy**: `exit-code: '0'` no CI (reporting-only) até o upgrade ser validado.
- **Auth**: API key partilhada por aplicação; multiusuário real exige
  OIDC/JWT com namespace por `sub`.
- **GETs com prompts**: mantidos por didáctica; POST é o contrato de produção.
- **Prompt guard**: blocklist heurística, não substitui guardrails dedicados.

## 6. Próximos passos

### P0 — Upgrade coordenado de dependências

1. Consultar metadados Maven oficiais (Boot, Spring AI, Alibaba, springdoc) e
   escolher uma matriz compatível; registar matriz, fontes, riscos e
   alternativas rejeitadas no `ANALISE.md`.
2. Atualizar `pom.xml` de forma coordenada (parent, `spring-ai.version`,
   `spring-ai-alibaba.version`, Testcontainers, springdoc, driver PG via BOM).
3. `./mvnw -B dependency:tree -Dscope=runtime` + `dependency:analyze` — sem
   conflitos, snapshots ou drivers duplicados.
4. `./mvnw -B -DskipTests compile` → testes unitários
   (`*UnitTest,ProdAuthGuardTest`) → integração focada → `./mvnw -B verify`.
5. E2E: `E2E_REDIS=true` (2 testes) e `E2E_PG=true` (`PgVectorE2EIT`).
6. `docker compose config --quiet`, `docker build .`, `git diff --check`.
7. Scan Trivy local/Docker; registar por advisory: componente, versão corrigida,
   impacto, breaking change, decisão. Só depois: `exit-code: '1'` +
   `ignore-unfixed: true` no CI.

### P1 — Documentação e commits

8. Atualizar `README` (versões, total de testes = 61, E2E, Trivy) e
   `ANALISE.md` (matriz, advisories, resultados).
9. Revisão do diff completo (`git diff main...HEAD`, `git status`).
10. Commits separados, nesta ordem — **concluído** (`7bcd5db`…`58d888f`):
    - `docs: add project report and upgrade plan` (apenas este ficheiro);
    - `fix: per-endpoint rate limit key` (`RateLimitInterceptor`, `README`,
      `RateLimitPerPathTest`);
    - `test: lock semantic cache TTL eviction` (`SemanticCacheUnitTest`);
    - `build: raise jacoco floor to 0.65/0.54` (`pom.xml`).
11. **Sem `git push` sem autorização explícita.**

### P2 — Evoluções futuras

- OIDC/JWT por usuário (Spring Security, namespace por `sub`/`tenant`).
- Restringir prompts a POST em produção.
- Bulkhead de concorrência, TTL de memória de chat, exclusão de conversas.
- Guardrails dedicados de entrada/saída além do `PromptGuard`.

## 7. Definição de pronto

- [ ] matriz de versões documentada no `ANALISE.md`
- [ ] POM actualizado sem conflitos ou pins parciais
- [ ] `mvn verify` verde (61+ testes, pisos JaCoCo actuais)
- [ ] E2E Redis verde
- [ ] E2E pgvector verde
- [ ] advisories corrigíveis resolvidas ou justificadas
- [ ] Trivy bloqueante (`exit-code: '1'`)
- [ ] README e ANALISE sincronizados
- [ ] code review sem achados críticos
- [x] quatro commits do ciclo 2 criados e pushados (§6.10, `7bcd5db`…`58d888f`)
- [x] nenhum push sem autorização
