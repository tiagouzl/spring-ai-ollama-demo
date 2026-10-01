# Global SDD + PRD + TDD + Hooks — Design (v2)

- Data: 2026-10-01 | Escopo: global | v2 incorpora revisão da v1 (4 bloqueantes + inconsistências + lacunas)
- Status: aguardando review da v2

## 0. Premissas corrigidas (resposta à revisão v1)

- `tool_loop_guardrails` (Hermes) é anti-loop — conta repetição de falhas (`exact_failure`, `same_tool_failure`, `idempotent_no_progress`) — e NÃO bloqueia comandos destrutivos. Bloqueio destrutivo no Hermes = só hook pre-exec. (Verificado em `~/.hermes/config.yaml`.)
- `permission.bash` (OpenCode) é bloqueio declarativo (`allow`/`ask`/`deny`, wildcard `*`/`?`, última regra vence) SEM mensagem customizada. Fonte: `opencode.ai/docs/permissions` em 01/10/2026. A promessa "deny explica" foi removida da v1; mensagem custom = plugin (`tool.execute.before`) na Fase 2.
- Deny-list por string é contornável — é guard-rail contra acidente do agente, NÃO contra adversário. Bypasses conhecidos fora da promessa: `git push -f`, refspec `+main`, `rm -fr /`, `rm -rf /*`, `rm -rf ~`, `sudo rm …`, `bash -c "…"`, `find / -delete`, variação de caixa em `DROP`, `| sudo sh`, `sh <(curl …)`.
- Sem escape hatch o deny total será desligado — v1 separa `deny` de `ask`.

## 1. Arquitetura (fonte única + adapters)

- Fonte única: `~/.agents/` — `AGENTS.md` + `skills/` (`tdd`, `to-spec`, `brainstorming`, `writing-plans`, `executing-plans`, `systematic-debugging`). Sem skill nova.
- Adapters: OpenCode `permission` em `~/.config/opencode/opencode.json`; Hermes hook pre-exec + `security.redact_secrets` em `~/.hermes/`.
- Descoberta (verificar ANTES de editar, tarefa do plano): como cada agente carrega a fonte única — OpenCode lê `instructions` do config? `AGENTS.md` global de que path? Hermes lê skills de que path além de `trusted_project_dirs`? Se precisar de symlink ou `instructions`, entra no plano como passo 0. Sem isso a "fonte única" não é lida.

## 2. SDD / PRD / Specs + sucesso mensurável + data-flow

- Fluxo: sessão → `brainstorming` → PRD curto → spec em `docs/superpowers/specs/` → review → `writing-plans` → `executing-plans` com `tdd`.
- PRD mínimo: problema, usuário, sucesso mensurável, fora-de-escopo, 3–5 critérios de aceite.
- Sucesso mensurável da v1: (a) 0 comandos da lista positiva executados no dry-run; (b) 0 falsos positivos na lista negativa; (c) 1 sessão SDD completa no repo piloto.

## 3. TDD em v1 = instrução, sem enforcement (dito com todas as letras)

- V1 NÃO tem gate de TDD. O "warn" vive em ~4 linhas de `AGENTS.md` (prompt, não enforcement) + skill `tdd` existente. Falsa sensação de cobertura seria pior que admitir.
- Fase 2 (hard-gate via hook) só após critério de promoção (§8).

## 4. OpenCode `permission` — listas explícitas (13 deny + 9 ask)

Deny (acidente irreversível, sem uso legítimo interativo):
`rm -rf /`, `rm -fr /`, `rm -rf /*`, `rm -rf ~`, `rm -rf ~/*`, `sudo rm *`, `mkfs *`, `chmod -R 777 *`, `*| sh`, `*| bash`, `*| sudo sh`, `*| sudo bash`, `find / -delete`

Ask (uso legítimo existe → escape hatch `once`/`always`/`reject`):
`git push *--force*`, `git push *-f*`, `git push * +*`, `git reset --hard*`, `dd *`, `*DROP TABLE*`, `*DROP DATABASE*`, `*drop table*`, `*drop database*` (caixa dupla porque o match é literal)

Semântica de glob a fixar no dry-run (doc não diz prefixo vs string inteira): `rm -rf /tmp/x` e `rm -rf ./build` DEVEM passar; se o padrão `rm -rf /` casar por prefixo, estreitar para `rm -rf / ` com sentinela ou reordenar (última regra vence).

## 5. Hermes espelho

- `tool_loop_guardrails`: inalterado (anti-loop, não é bloqueio destrutivo).
- `security.redact_secrets: true` + teste de não-corrupção (hashes/logs de debug legíveis — item de teste, não premissa).
- `~/.hermes/hooks/` (hoje vazio): `pre-exec-destructive.sh` (bloqueia, espelha §4) + `pre-spec-gate.sh` (warn SDD, sem bloqueio). Formato exato a confirmar via `hermes hooks --help` + doc oficial antes de escrever.
- Sem segredo em config versionada (env `*_KEY` como hoje).

## 6. Testes: 13 positivos + 8 negativos

- Positivos (devem bloquear/pedir): um por padrão do §4–§5.
- Negativos (devem passar sem atrito): `git push --force-with-lease`, `git reset --soft`, `rm -rf ./build`, `rm -rf /tmp/x`, `curl -O <url>` (download sem pipe), `grep -r …`, `git status`, `git push origin main`.
- Nota: `git push *` puro fica `allow` — regra específica de `--force` não pode vazar para push normal (última-regra-vence exige ordem explícita no plano).

## 7. Rollback

- Antes de editar: backup com timestamp dos 2 configs (`opencode.json`, `config.yaml`) + commit da spec. Reversão = restaurar backup, 1 comando, documentado no plano.

## 8. Promoção para Fase 2 (observável, sem automatismo)

- Fase 2 (spec-gate hard, TDD hard, plugin de mensagem custom) só com: 20 sessões sem falso positivo no `ask` + 1 SDD end-to-end no piloto + review explícito. Sem data, sem auto-promoção.

## 9. Caminho das specs

- Piloto: `docs/superpowers/specs/` deste repo. Globais canônicas: `~/docs/superpowers/specs/`; este repo guarda cópia do piloto. Sem duplicação de conteúdo vivo.

## Arquivos tocados (v1)

1. `~/.agents/AGENTS.md` (+~4 linhas instrução SDD/TDD — prompt, não gate).
2. `~/.config/opencode/opencode.json` — `permission.bash` (§4) + `instructions` se §1 exigir.
3. `~/.hermes/config.yaml` — `redact_secrets` (guardrails anti-loop intactos).
4. `~/.hermes/hooks/pre-exec-destructive.sh` — novo (bloqueio).
5. `~/.hermes/hooks/pre-spec-gate.sh` — novo (warn).

## Fora de escopo v1

Plugin de mensagem custom, TDD hard-block, spec-gate hard, hooks por projeto, CI-sidecar, PRD longo, telemetria.
