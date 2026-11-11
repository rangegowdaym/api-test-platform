---
name: add-step-definition
description: Add or fix a step definition. Use when a dry run reports an undefined or ambiguous step, or a hand-written feature needs a new step.
---

# Steps

- Per-tag steps (`steps/generated/<Tag>Steps.java`) are written by the AI: re-run `ai automation --tag <tag>`
  (skill `testcases-to-features`) instead of patching them by hand, so the next generation keeps the fix.
- Shared steps used by many tags (`Given test case "<id>"`, status, schema, field checks) live in
  `src/test/java/com/qa/automation/steps/ApiSteps.java`. Add one there only when several tags need it; it then
  becomes part of the framework the AI sees.
- Hand-written flows: a domain `*Steps` class backed by a service (skill `add-api-service`).

Rules: glue only (no Rest Assured, no JSON parsing in steps), one public constructor (PicoContainer), state in
`ScenarioContext`, assertions through `ResponseAssert`. Check with `mvn -q test -Dcucumber.execution.dry-run=true`.
