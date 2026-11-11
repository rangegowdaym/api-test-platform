---
name: ai-assist
description: Answer the platform's queued AI tasks (test cases, spec audit, automation, RCA, heal) as the IDE agent when no LLM token is configured. Use for /ai, "answer the queue", or any AI task of the platform.
---

# Answering queued AI tasks (you are the model)

```bash
java -jar target/api-test-platform.jar ai pending          # what is queued (task, args, answered/requests)
# queue a task yourself when asked:  ai testcases --spec specs/x.yaml | ai automation [--tag t] | ai rca | ai heal | ai spec-audit --spec ...
java -jar target/api-test-platform.jar ai apply            # validate + apply every answered task
```

For each pending task open `target/ai/<task>/requests.md`. The top has the instructions (the task's prompt); each
"Request" section names its answer file (or answer **folder** for automation/heal). Write ONLY the answer there.

## Per task

| Task | Answer | Look at |
| --- | --- | --- |
| testcases | one JSON array of cases (`answers/<part>.json`) | the spec text in the request; reuse listed ids |
| spec-audit | markdown with a verdict | the spec |
| automation | folder `answers/<tag>/` with the real files at their project paths, optional `notes.md` | approved cases, framework source in the request, existing files |
| rca | one JSON object `{entries, triage}` | `target/evidence/<caseId>.json`, history in the request |
| heal | same folder as automation; only `notes.md` = "no automation change" (API bug) | failures in the request, `target/evidence/` |

## Then
1. `ai apply`. Lines with "rejected" say exactly why: fix that answer and run `ai apply` again.
2. After automation or heal: `mvn -q test -Dcucumber.execution.dry-run=true` (must pass), then
   `./scripts/run-tests.sh <env> "<tags>"`. After a run with failures: `ai rca` → answer → apply; then `ai heal` →
   answer → apply → run again (once). Stop when only API-side failures remain.
3. Reply in 3-5 lines: what was applied, what is left for a human (review N cases at http://localhost:8080,
   review the diff, API bugs by owner), report `reports/index.html#rca`.

## Rules
- Results only through `ai apply`. Never set a case APPROVED, never edit `testcases/`, generated code or `rca.json` directly.
- Never weaken, drop or skip an expectation; a feature must keep one scenario per APPROVED case with its approved status.
- Scenarios create their own data (`ctx.vars.put`, `ctx.addCleanup`) instead of relying on `testdata.*` config.
- No secrets in answers; the requests are masked, keep them that way.
