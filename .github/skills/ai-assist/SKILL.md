---
name: ai-assist
description: Run the platform's AI tasks (API doc to OpenAPI, spec audit, business test cases, RCA narrative, triage note) either through the configured LLM API or by answering them yourself as the IDE agent. Use when someone asks for AI help on a spec, extra test cases, or a better RCA explanation, or when no LLM token is configured.
---

# AI assist: one command, two ways to answer

| Task | Input | Prompt | Result (after validation) |
| --- | --- | --- | --- |
| `doc-to-openapi` | `--doc docs/api.md` | `prompts/doc-to-openapi.md` | `specs/<doc>.openapi.yaml` (parsed as OpenAPI first) |
| `spec-audit` | `--spec specs/x.yaml` | `prompts/spec-audit.md` | `target/platform/spec-audit-<spec>.md` |
| `testcase-augment` | `--spec specs/x.yaml [--context "..."]` | `prompts/testcase-augment.md` | cases in `testcases/` as **PENDING_REVIEW** (schema-checked, never approved) |
| `rca-narrative` | last run | `prompts/rca-analysis.md` | summary/cause/fix in `target/rca/rca.json`, tickets, report (category kept) |
| `triage-summary` | last run | `prompts/triage-summary.md` | `target/rca/triage.md` |

## With an LLM token (LLM_PROVIDER + key, README "LLM")
```bash
java -jar target/api-test-platform.jar ai testcase-augment --spec specs/x.yaml --mode llm
```

## As the IDE agent (no token: GitHub Copilot agent mode, Claude Code)
1. Export: `java -jar target/api-test-platform.jar ai <task> [--spec ...|--doc ...] --mode agent`
2. Open `target/ai/<task>/requests.md`. For each "Request" section: follow the instructions at the top and write
   ONLY the answer (JSON / YAML / Markdown as stated) to the answer file it names
   (`target/ai/<task>/answers/<id>.<ext>`). Check claims against `specs/`, `testcases/`, `target/evidence/`.
3. Apply: `java -jar target/api-test-platform.jar ai <task> --apply --by copilot-agent`
4. If lines say "rejected", fix those answer files and apply again. Report what was applied.

## Rules
- Never write the result files directly (rca.json, the report, testcases/, specs/): `--apply` does it, after the
  same validation an LLM answer gets. Never set a case APPROVED; humans approve in the UI.
- Never change an RCA category or owner; say "Category may be wrong:" in probableCause instead.
- No secrets in answers. The requests are masked; keep them that way.
