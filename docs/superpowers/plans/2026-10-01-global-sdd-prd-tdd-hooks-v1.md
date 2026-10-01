# Global SDD + PRD + TDD + Hooks v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ligar guardrails anti-acidente (deny + ask) no OpenCode e no Hermes, com instrução SDD/TDD via prompt, sem enforcement falso.

**Architecture:** Descoberta primeiro (formato de hooks do Hermes, path de AGENTS.md, semântica do glob), depois backup, depois edição mínima de 2 configs + 2 scripts + 4 linhas de instrução, fechando com dry-run positivo/negativo e 1 sessão SDD piloto.

**Tech Stack:** OpenCode `permission.bash` (allow/ask/deny, wildcard `*`/`?`), Hermes hooks bash + `security.redact_secrets`, skills `tdd`/`to-spec` existentes.

**Spec:** `docs/superpowers/specs/2026-10-01-global-sdd-prd-tdd-hooks-design.md` (v2)

## Global Constraints

- Nenhuma skill nova; reusar `tdd`, `to-spec`, `brainstorming`, `writing-plans`, `executing-plans`.
- Nenhum segredo em config versionada; chaves só via env `*_KEY`.
- Backup com timestamp dos 2 configs antes de editar; reversão em 1 comando.
- Não assumir sintaxe: confirmar na doc oficial / `--help` antes de escrever.
- V1 = instrução (prompt), sem enforcement de SDD/TDD; não gerar falsa sensação de cobertura.
- Commits só dos arquivos do plano; `README.md`/`relatorio.md`/`opencode-cont` intocados.

---

### Task 0: Descoberta (formato hooks, paths, glob)

**Files:**
- Create: `/tmp/sdd-v1-discovery.md`
- Modify: none
- Test: none (fact-finding; outputs feed Tasks 2–6)

**Interfaces:**
- Consumes: nothing
- Produces: `/tmp/sdd-v1-discovery.md` with exactly three answers: (a) Hermes hook wiring, (b) AGENTS.md target path, (c) glob semantics verdict

- [ ] **Step 1: Descobrir wiring dos hooks do Hermes**

Run: `hermes hooks --help 2>&1 | head -40; ls ~/.hermes/hooks/`
Expected: help text describing when hooks fire, argv/env passing, exit-code convention (which code blocks vs warns)
Record in `/tmp/sdd-v1-discovery.md` under `## hooks`: the invocation signature verbatim (e.g. `pre-exec <command>` / env var name / exit codes). If `hooks --help` does not exist, run `hermes --help 2>&1 | grep -i -A3 hook` and record that hooks are undocumented → Task 3 falls back to standalone script + manual registration note.

- [ ] **Step 2: Descobrir como cada agente carrega a fonte única**

Run: `grep -n instructions ~/.config/opencode/opencode.json; ls ~/.agents/AGENTS.md 2>&1; grep -n trusted_project_dirs -A3 ~/.hermes/config.yaml`
Expected: know whether opencode.json has `instructions`, whether `~/.agents/AGENTS.md` exists, Hermes trusted dirs list
Record under `## discovery`: the exact file Task 6 must edit. Rule: if `~/.agents/AGENTS.md` is absent, Task 6 edits `~/.config/opencode/AGENTS.md` instead and notes the correction (spec §1 path was wrong).

- [ ] **Step 3: Registrar e commitar a descoberta**

Run: `cat /tmp/sdd-v1-discovery.md`
Expected: three sections filled, no TBD
Then: `git add docs/superpowers/plans/2026-10-01-global-sdd-prd-tdd-hooks-v1.md` — no, nothing to commit yet; discovery file stays in /tmp (transient). Proceed to Task 1.

### Task 1: Backup dos 2 configs

**Files:**
- Create: `~/.config/opencode/opencode.json.bak-20261001`, `~/.hermes/config.yaml.bak-20261001` (outside repo, never committed)
- Modify: none
- Test: byte-compare

**Interfaces:**
- Consumes: nothing
- Produces: restorable backups; rollback = `cp <bak> <orig>`

- [ ] **Step 1: Copiar configs com timestamp**

Run: `cp ~/.config/opencode/opencode.json ~/.config/opencode/opencode.json.bak-20261001 && cp ~/.hermes/config.yaml ~/.hermes/config.yaml.bak-20261001 && ls -la ~/.config/opencode/opencode.json.bak-20261001 ~/.hermes/config.yaml.bak-20261001`
Expected: both files listed, non-zero size

- [ ] **Step 2: Verificar integridade do backup**

Run: `diff ~/.config/opencode/opencode.json ~/.config/opencode/opencode.json.bak-20261001 && diff ~/.hermes/config.yaml ~/.hermes/config.yaml.bak-20261001 && echo BACKUPS-OK`
Expected: `BACKUPS-OK`, no diff output

### Task 2: OpenCode `permission.bash` (13 deny + 9 ask + 1 allow de escape)

**Files:**
- Modify: `~/.config/opencode/opencode.json` (`permission.bash` key only)
- Test: sacrificial dir `/tmp/pony-probe/x` + `opencode run` denials

**Interfaces:**
- Consumes: Task 0 glob notes (ordering rule: specific-after-general, last-match-wins)
- Produces: enforced deny/ask in OpenCode; probe verdict on prefix semantics

- [ ] **Step 1: Aplicar o bloco permission (única edição)**

Set `permission.bash` to exactly this object (order matters, last-match-wins; keep all other top-level keys untouched):

```json
"bash": {
  "rm -rf /": "deny",
  "rm -fr /": "deny",
  "rm -rf /*": "deny",
  "rm -rf ~": "deny",
  "rm -rf ~/*": "deny",
  "sudo rm *": "deny",
  "mkfs *": "deny",
  "chmod -R 777 *": "deny",
  "*| sh": "deny",
  "*| bash": "deny",
  "*| sudo sh": "deny",
  "*| sudo bash": "deny",
  "find / -delete": "deny",
  "git push *--force*": "ask",
  "git push *-f*": "ask",
  "git push * +*": "ask",
  "git reset --hard*": "ask",
  "dd *": "ask",
  "*DROP TABLE*": "ask",
  "*DROP DATABASE*": "ask",
  "*drop table*": "ask",
  "*drop database*": "ask",
  "git push *--force-with-lease*": "allow"
}
```

Validate: `python3 -c "import json;json.load(open('$HOME/.config/opencode/opencode.json'));print('JSON-OK')`
Expected: `JSON-OK`

- [ ] **Step 2: Sonda de semântica do glob (dir sacrificial)**

Run: `mkdir -p /tmp/pony-probe/x && echo MARKER > /tmp/pony-probe/x/marker.txt && opencode run "Execute exactly: rm -rf /tmp/pony-probe/x" 2>&1 | tail -5; cat /tmp/pony-probe/x/marker.txt 2>&1`
Expected: denial (deny/ask, never silent execution). Two readings: if `marker.txt` still prints MARKER → blocked, prefix semantics do NOT over-match — record verdict. If marker file is gone → pattern matched by prefix → tighten `rm -rf /` to sentinel form and re-run Step 2. Either way, record the verdict in the commit message.

- [ ] **Step 3: Limpar sonda**

Run: `rm -rf /tmp/pony-probe && echo CLEAN`
Expected: `CLEAN`

### Task 3: Hook `pre-exec-destructive.sh` do Hermes (bloqueio, 13 padrões)

**Files:**
- Create: `~/.hermes/hooks/pre-exec-destructive.sh` (executable; registration per Task 0 wiring — if hooks are undocumented, create the script anyway + note manual step)
- Test: direct invocation with sample commands, exit codes asserted

**Interfaces:**
- Consumes: `/tmp/sdd-v1-discovery.md` `## hooks` (argv vs env input)
- Produces: exit-1-on-deny enforcement; ask-patterns logged to stderr + exit 0 (no ask channel in v1)

- [ ] **Step 1: Escrever o script (corpo fixo, input pela descoberta)**

```bash
#!/usr/bin/env bash
# pre-exec-destructive.sh — v1: anti-acidente, NAO anti-adversario.
# Input: full command in "$1" (or "$HERMES_EXEC_CMD" if Task 0 found env passing — pick ONE per discovery, delete the other line).
cmd="${1:-$HERMES_EXEC_CMD}"
case "$cmd" in
  "rm -rf /"|"rm -fr /"|"rm -rf /"*|"rm -rf ~"|"rm -rf ~/"*|sudo\ rm\ *|mkfs\ *|chmod\ -R\ 777\ *|*"| sh"|*"| bash"|*"| sudo sh"|*"| sudo bash"|"find / -delete")
    echo "BLOCKED (destructive, no legitimate interactive use): $cmd" >&2; exit 1;;
esac
case "$cmd" in
  git\ push\ *--force*|git\ push\ *-f*|git\ push\ *\ +*|git\ reset\ --hard*|dd\ *|*DROP\ TABLE*|*DROP\ DATABASE*|*drop\ table*|*drop\ database*)
    echo "WARN (needs human confirmation out-of-band in v1): $cmd" >&2; exit 0;;
esac
exit 0
```

Run: `chmod +x ~/.hermes/hooks/pre-exec-destructive.sh && bash -n ~/.hermes/hooks/pre-exec-destructive.sh && echo SYNTAX-OK`
Expected: `SYNTAX-OK`

- [ ] **Step 2: Testar 3 bloqueios + 3 passes (amostra; matriz completa no Task 7)**

Run: `for c in "rm -rf /" "mkfs /dev/sda1" "curl http://x | sh"; do ~/.hermes/hooks/pre-exec-destructive.sh "$c" >/dev/null 2>&1; echo "$c -> $?"; done; for c in "git status" "git push origin main" "rm -rf ./build"; do ~/.hermes/hooks/pre-exec-destructive.sh "$c" >/dev/null 2>&1; echo "$c -> $?"; done`
Expected: `1`, `1`, `1`, then `0`, `0`, `0` — any deviation is a bug in the case globs, fix before proceeding

### Task 4: Hook `pre-spec-gate.sh` (warn SDD, nunca bloqueia)

**Files:**
- Create: `~/.hermes/hooks/pre-spec-gate.sh` (executable)
- Test: exit code always 0

**Interfaces:**
- Consumes: Task 0 wiring (which tool events fire it: edit/write/bash)
- Produces: warn-only reminder; enforcement explicitly absent in v1

- [ ] **Step 1: Escrever o script**

```bash
#!/usr/bin/env bash
# pre-spec-gate.sh — v1: reminder only, NEVER blocks (no SDD enforcement in v1, spec v2 §3).
echo "REMINDER: superpowers flow is brainstorming -> PRD -> spec (docs/superpowers/specs/) -> writing-plans. No gate in v1." >&2
exit 0
```

Run: `chmod +x ~/.hermes/hooks/pre-spec-gate.sh && ~/.hermes/hooks/pre-spec-gate.sh; echo "exit=$?"`
Expected: reminder on stderr, `exit=0`

### Task 5: `redact_secrets` + teste de não-corrupção

**Files:**
- Modify: `~/.hermes/config.yaml` (uncomment/set `security.redact_secrets: true` only)
- Test: hashes/logs legíveis após a mudança

**Interfaces:**
- Consumes: Task 1 backup
- Produces: redaction on, or reverted with reason

- [ ] **Step 1: Ligar redact e validar YAML**

Uncomment/add under `security:`: `redact_secrets: true`. Validate with: `python3 -c "import yaml,sys;yaml.safe_load(open('$HOME/.hermes/config.yaml'));print('YAML-OK')"` (fallback: `hermes config check 2>&1 | head -5` if pyyaml missing)
Expected: `YAML-OK` (or hermes config accepted)

- [ ] **Step 2: Teste de não-corrupção**

Run a session command that prints a sha256 hash and a debug log line, e.g. `echo -n hello | sha256sum`, and confirm the 64-hex output is intact and readable (redactor must not eat hashes). If output is corrupted/masked, revert this task only: `cp ~/.hermes/config.yaml.bak-20261001 ~/.hermes/config.yaml` and record the revert reason in the final commit message.

### Task 6: Instrução SDD/TDD (prompt, não gate)

**Files:**
- Modify: discovery target from Task 0 (`~/.agents/AGENTS.md` if it exists, else `~/.config/opencode/AGENTS.md`): append 4 lines
- Test: file contains the lines verbatim

**Interfaces:**
- Consumes: `/tmp/sdd-v1-discovery.md` `## discovery`
- Produces: v1 "warn" = prompt text; explicitly not enforcement

- [ ] **Step 1: Anexar as 4 linhas (sem tocar no resto do arquivo)**

```markdown
## SDD v1 (prompt, sem enforcement)
Fluxo: brainstorming → PRD curto → spec em docs/superpowers/specs/ → writing-plans → executing-plans com tdd. Sem spec aprovada, sem código.
```

Run: `tail -6 <target> | grep -c "Sem spec aprovada" `
Expected: `1`

### Task 7: Dry-run matriz completa (13 positivos + 8 negativos, OpenCode)

**Files:**
- Modify: none
- Test: this task IS the test; record results table in commit message

**Interfaces:**
- Consumes: Task 2 enforcement
- Produces: pass/fail table; any fail → fix Task 2, re-run

- [ ] **Step 1: Positivos — nenhum pode executar**

For each of the 22 strings copied verbatim from Task 2 (13 deny + 9 ask), run the template below with `<cmd>` replaced by the literal string. Anchor example (run exactly this first):

Run: `opencode run "Execute exactly: rm -rf /" 2>&1 | tail -3`
Expected: denial text, exit non-zero, filesystem untouched

Then repeat the template for the remaining 21 strings. For the 9 ask patterns, assert the command is NOT auto-executed (prompted or blocked in non-interactive mode counts as pass; verify side-effect absence, e.g. `git log --oneline -1` unchanged after the `git reset --hard` attempt).
Expected: 22/22 not-executed. Record per-item outcome.

- [ ] **Step 2: Negativos — todos passam sem atrito**

Run each and assert success: `git push --force-with-lease` (dry? use `git push --dry-run --force-with-lease` to avoid network), `git reset --soft HEAD~0`, `rm -rf ./build-tmp-probe` (create first), `ls /tmp`, `curl -sI https://example.com | head -1`, `grep -r probe .`, `git status --short`, `git push --dry-run origin main`.
Expected: 8/8 execute without permission friction. Any friction = pattern over-match → fix Task 2 ordering and re-run.

### Task 8: Sessão SDD piloto (métrica (c) da spec)

**Files:**
- Create: `docs/superpowers/specs/2026-10-01-pilot-sdd-proof.md` (toy topic, ~20 lines, kept as evidence)
- Test: session followed brainstorming → spec → review with zero code written before approval

**Interfaces:**
- Consumes: Task 6 instruction
- Produces: proof file + transcript note that no implementation preceded approval

- [ ] **Step 1: Rodar sessão piloto num tópico brinquedo e salvar a spec**

Pick a trivial topic (e.g. "rename pilot probe script"), run the flow in one session, save the toy spec. Assert: `git status --short docs/superpowers/specs/` shows only the new file; no `src/` changes exist from the session.
Expected: spec file exists, zero implementation diff.

### Task 9: Revisão final + commit + baseline de promoção

**Files:**
- Modify: none (commit docs only)
- Test: `git status` shows only intended files

**Interfaces:**
- Consumes: all tasks
- Produces: commit + promotion baseline (0 sessions counted yet)

- [ ] **Step 1: Commit do plano + specs**

Run: `git status --short && git add docs/superpowers/plans/2026-10-01-global-sdd-prd-tdd-hooks-v1.md docs/superpowers/specs/2026-10-01-pilot-sdd-proof.md && git commit -m "docs: plano v1 SDD+TDD+hooks + prova piloto (dry-run 22/8, glob: PREFIX-BLOCKS|PREFIX-ALLOWS)"`
Expected: commit created; in the message, keep whichever verdict Task 2 Step 2 produced and delete the other. `README.md`/`relatorio.md` untouched.

- [ ] **Step 2: Registrar baseline de promoção Fase 2**

Append one line to the plan file? No — record here: promotion counter starts at 0/20 ask-sessions + 0/1 SDD end-to-end. Done when this task completes.
