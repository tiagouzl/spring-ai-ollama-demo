# Global SDD + PRD + TDD + Hooks — Design (v1)

- Data: 2026-10-01 | Escopo: global | Decisões: Global primeiro, Superpowers puro, Bloquear destrutivo inegociável
- Status: aprovado por seções, aguardando review da spec

## 1. Arquitetura (fonte única + adapters)

- Fonte única: `~/.agents/` — `AGENTS.md` + `skills/` (`tdd`, `to-spec`, `brainstorming`, `writing-plans`, `executing-plans`, `systematic-debugging`).
- Adapters finos, sem lógica duplicada:
  - OpenCode: `~/.config/opencode/opencode.json` (permission) + prompts/skills locais só referenciam `~/.agents/`.
  - Hermes: `~/.hermes/config.yaml` (`tool_loop_guardrails`, `security`) + `~/.hermes/hooks/` (2 scripts espelhados).
- Regra: nada de SDD/TDD implementado duas vezes; adapter só liga/desliga enforcement nativo.

## 2. SDD / PRD / Specs (superpowers puro)

- Fluxo v1: `brainstorming` → PRD curto → spec em `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` → `writing-plans` → `executing-plans`. Hard-gate: sem spec aprovada, sem código (warn em v1, hard em fase 2).
- PRD mínimo: problema, usuário, sucesso mensurável, fora-de-escopo, 3-5 critérios de aceite. YAGNI: sem PRD longo.
- Spec mínima: arquitetura, arquivos tocados, data-flow, erros, testes. Reusa `to-spec` / `to-tickets` quando existir tracker.

## 3. TDD (warn em v1)

- Reusa skill `tdd` existente; sem nova framework.
- v1: nudge (warn) — RED antes de GREEN, `verification-before-completion` antes de done. Fase 2: hard-gate (hook bloqueia implement sem teste falhando antes).
- Teste mínimo v1: 1 check executável por lógica não-trivial; resto YAGNI.

## 4. Hooks OpenCode (bloquear destrutivo)

- Manter `permission.bash.deny` existente (`rm -rf /`, `mkfs`, `dd`) e expandir deny: `git push --force`, `git reset --hard`, `drop table|database`, `chmod -R 777`, `curl|wget ... | sh`.
- Sintaxe exata de hooks opencode a confirmar na doc oficial antes de editar (não assumir schema).
- Falha de hook = bloqueia comando + mensagem curta com alternativa segura.

## 5. Hooks Hermes (espelho)

- `tool_loop_guardrails`: `warnings_enabled: true`, `hard_stop_enabled: true` em v1 só para destrutivos; `warn_after`/`hard_stop_after` atuais mantidos.
- `security.redact_secrets: true` (hoje comentado) — ligar.
- `~/.hermes/hooks/` (hoje vazio): 2 scripts `pre-exec-destructive.sh` + `pre-spec-gate.sh` (warn). Formato a confirmar via `hermes hooks --help` + doc oficial.
- Sem segredo em config versionada; chave via `*_KEY` env como hoje (`HERMES_CUSTOM_*`).

## 6. Erros, teste do próprio guardrail, rollout

- Erros: hook nunca trava sessão — deny explica + sugere comando seguro; fallback de modelo inalterado.
- Teste v1: dry-run de cada padrão deny (lista de 8 comandos proibidos) em ambos agentes + 1 SDD end-to-end num repo piloto antes de globalizar hard-gate.
- Rollout: v1 = destrutivo hard + SDD/TDD warn. Fase 2 = spec-gate hard + TDD hard. Sem fase 2 automática.

## Arquivos tocados (v1, só global)

1. `~/.agents/` — AGENTS.md (+4 linhas SDD/TDD gate), reuso skills, sem skill nova.
2. `~/.config/opencode/opencode.json` — expandir `permission`.
3. `~/.hermes/config.yaml` — `hard_stop_enabled`, `redact_secrets`.
4. `~/.hermes/hooks/pre-exec-destructive.sh` — novo, mínimo.

## Fora de escopo v1

CI-sidecar, PRD longo, TDD hard-block, hooks por projeto, telemetria.
