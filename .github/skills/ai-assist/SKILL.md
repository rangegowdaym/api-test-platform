---
name: ai-assist
description: Run the platform's AI tasks (test cases from a spec, automation code, RCA, spec audit) through the configured LLM API or by answering them yourself as the IDE agent. Use for any of these, especially when no LLM token is configured.
---

# AI assist: one command, two ways to answer

| Task | Input | Prompt | Result (after validation) |
| --- | --- | --- | --- |
| `testcases` | `--spec specs/x.yaml [--context "..."]` (any spec or API doc) | `prompts/testcases.md` | cases in `testcases/` as **PENDING_REVIEW**, merged with the stored ones |
| `automation` | `[--tag t]` (APPROVED cases) | `prompts/automation.md` | feature file + `<Tag>Api` + `<Tag>Steps` per tag (answer = files in the answer folder) |
| `rca` | last run | `prompts/rca.md` | `target/rca/rca.json` (category, owner, grouping, fix, tickets), `triage.md`, report |
| `spec-audit` | `--spec specs/x.yaml` | `prompts/spec-audit.md` | `target/platform/spec-audit-<spec>.md` |

## With an LLM token (LLM_PROVIDER + key, README "LLM")
```bash
java -jar target/api-test-platform.jar ai testcases --spec specs/x.yaml --mode llm
```

## As the IDE agent (no token: GitHub Copilot agent mode, Claude Code)
1. Export: `java -jar target/api-test-platform.jar ai <task> [--spec ...|--doc ...] --mode agent`
2. Open `target/ai/<task>/requests.md`. For each "Request" section: follow the instructions at the top and write
   ONLY the answer (JSON / YAML / Markdown as stated) to the answer file it names
   (`target/ai/<task>/answers/<id>.<ext>`). For `automation` the answer is a FOLDER: write the real files there
   at their project paths. Check claims against `specs/`, `testcases/`, `target/evidence/`.
3. Apply: `java -jar target/api-test-platform.jar ai <task> --apply --by copilot-agent`
4. If lines say "rejected", fix those answer files and apply again. Report what was applied.

## Rules
- Write results only through `--apply` (it validates exactly like an LLM answer). Never set a case APPROVED.
- Never weaken an assertion to make a test pass. Testers review everything you write (git diff, report).
- No secrets in answers. The requests are masked; keep them that way.
