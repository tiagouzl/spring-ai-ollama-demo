# Pilot SDD Proof — Rename Probe Script (toy topic)

- Data: 2026-10-01 | Escopo: piloto SDD métrica (c) da spec
- Status: aprovado · zero código escrito antes da aprovação

## Problema (2 linhas)

O script de prova no repositório está nomeado `probe.sh`, mas a convenção do projeto exige prefixo `pilot-`. Renomear sem alterar conteúdo.

## Critérios de aceite

1. Arquivo renomeado de `probe.sh` → `pilot-probe.sh` em `src/tools/`.
2. Qualquer referência interna ao caminho antigo atualizada no README.
3. Diff de implementação = 0 (apenas rename + docs); testes existentes continuam passando.

## Arquivos que uma implementação real tocaria

- `src/tools/probe.sh` → renomeado para `src/tools/pilot-probe.sh` (não criado)
- `README.md` (não criado; linha de referência atualizada)
- NENHUM outro arquivo sob `src/`

## Aprovação · sem código antes do spec

Nenhum comando de escrita, rename, ou edição foi executado antes deste arquivo ser salvo.
O diff de implementação esperado é vazio; o único artefato desta sessão é este spec.
