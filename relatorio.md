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
- **Upgrade coordenado concluído** (§6.1–§6.7): Boot 3.4.5→**3.5.16**,
  Spring AI 1.0.1→**1.1.8**, Alibaba 1.0.0.4→**1.1.2.4-security-fix**;
  verify 61/61, E2E 7/7, Trivy 7→1 achado (advisory em waiver).
  Matriz, breaking changes e advisories em `ANALISE.md` §26–§27.

## 2. Estado do repositório

Working tree **limpa**; ciclos 1–3 mergeados em `main` via **PR #12**
(merge commit `c7ba86c`); branch `chore/dependency-security-upgrade`
removida após o merge. Todos os pushes foram autorizados explicitamente.

Commits do ciclo 2 (agora na história de `main`):

| Commit | Mensagem |
|---|---|
| `7bcd5db` | docs: add project report and upgrade plan |
| `b3e5b42` | fix: per-endpoint rate limit key |
| `4938132` | test: lock semantic cache TTL eviction |
| `58d888f` | build: raise jacoco floor to 0.65/0.54 |

Ciclo 3 (upgrade): `5a76470` (build), `d02b247` (docs), `becbb89`
(lições de memória), `cd6217c` (tag `v0.36.0` do trivy-action — apanhada
pelo CI do PR; `0.36.0` sem prefixo não existe).

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

- ~~CORS wildcard por omissão~~ — **resolvido** (deny-by-default: base sem CORS,
  `*` só no dev, prod fail-fast). API key opcional = escolha de demo, mantém-se.
- PromptGuard contornável (heurístico, aceitável em demo — defence in depth).
- ~~Testes frágeis (`RagDefaultStoreTest` instanceof, `OpenApiDocsTest`)~~ —
  **resolvidos** (contrato "bean único, não-pgvector" + parse JSON do spec).
- ~~Sem testes de concorrência do rate limiter nem de frases custom do guard~~ —
  **resolvidos** (`RateLimitConcurrencyTest`, `PromptGuardTest`).
- Fail-open Redis em multi-instância multiplica a taxa efectiva.

## 4. Validações

| Validação | Quando | Resultado |
|---|---|---|
| `./mvnw clean verify` ×3 com matriz nova (61 testes, JaCoCo 0.65/0.54) | ciclo 3, upgrade | **BUILD SUCCESS** (3×) |
| E2E Redis + pgvector + Ollama na stack final (7 testes) | ciclo 3, upgrade | **7/7 verdes** |
| `docker compose config --quiet`, `docker build .`, `git diff --check` | ciclo 3, upgrade | **verde** |
| Trivy fs (7→1) e imagem (4→1; OS 0) | ciclo 3, upgrade | **1 advisory (mcp, waiver)** |
| `./mvnw verify` (66 testes: +5) — achados §3 (CORS deny-by-default, testes) | pós-PR #12 | **BUILD SUCCESS** |
| Testes de rate limit (`RateLimit*`, `ApiKeyRateLimitIsolation`, `RedisFallback`) | ciclo 2 | 5/5 verdes |
| `SemanticCacheUnitTest` (7, incl. TTL) | ciclo 2 | verde |
| `./mvnw verify` (59 testes) + JaCoCo | ciclo 1 (`8ddfc47`) | verde |
| E2E Redis (`SemanticCacheRedisE2EIT`, `RedisRateLimitE2EIT`) | ciclo 1 | verde |
| `graft build` | ciclo 1 | verde |

## 5. Riscos residuais

- **Dependências**: matriz P0 aplicada e validada (ANALISE §26); advisories
  corrigíveis resolvidas e restantes justificadas (ANALISE §27). Residual:
  `mcp-core` 0.18.3 (CVE-2026-35568) até o Spring AI subir o MCP SDK —
  endpoints MCP não usados pela app, waiver documentado em `.trivyignore`.
- **Trivy**: `exit-code: '1'` no CI (bloqueante) + `ignore-unfixed`.
- **Auth**: API key partilhada por aplicação; multiusuário real exige
  OIDC/JWT com namespace por `sub`.
- **GETs com prompts**: mantidos por didáctica; POST é o contrato de produção.
- **Prompt guard**: blocklist heurística, não substitui guardrails dedicados.

## 6. Próximos passos

### P0 — Upgrade coordenado de dependências (**concluído** — ver `ANALISE.md` §26–§27)

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
- ~~Restringir prompts a POST em produção~~ — feito (`app.post-only-prompts` + `PromptGetGuardFilter`; `ANALISE.md` §29).
- Bulkhead de concorrência e exclusão de conversas (TTL da memória de chat — feito, `ANALISE.md` §30).
- Guardrails dedicados de entrada/saída além do `PromptGuard`.

## 7. Definição de pronto

- [x] matriz de versões documentada no `ANALISE.md` (§26)
- [x] POM actualizado sem conflitos ou pins parciais
- [x] `mvn verify` verde (61 testes, pisos JaCoCo 0.65/0.54)
- [x] E2E Redis verde (2/2, stack final)
- [x] E2E pgvector verde (2/2)
- [x] E2E Ollama verde (3/3, stack final)
- [x] advisories corrigíveis resolvidas ou justificadas (`ANALISE.md` §27)
- [x] Trivy bloqueante (`exit-code: '1'` + `.trivyignore`)
- [x] README e ANALISE sincronizados
- [x] code review sem achados críticos (Standards + Spec; só smells de juízo e
      achados históricos fora do diff do upgrade)
- [x] quatro commits do ciclo 2 criados e pushados (§6.10, `7bcd5db`…`58d888f`)
- [x] nenhum push sem autorização
