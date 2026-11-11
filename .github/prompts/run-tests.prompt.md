---
description: Run the API tests, then RCA and the report
agent: agent
tools: ['read', 'search', 'execute']
argument-hint: "env tags, e.g. qa @smoke"
---
Follow the `run-api-tests` skill.
Environment: `${input:env:qa}`. Tag expression: `${input:tags:@smoke}`.

Run `./scripts/run-tests.sh <env> "<tags>"`, print the SUMMARY line and the report path
`reports/index.html#dashboard` (root causes: `#rca`). If there were failures, suggest running `/rca`.
The same run can be started by a human from the approval UI (button 3, Execute tests).
