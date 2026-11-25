---
description: Run the tests, then AI RCA and heal (automation/test-data faults only), re-run once
agent: api-qa
---
Environment `${input:env:qa}`, tags `${input:tags:@api}`. Follow the `run-api-tests` skill, then the "Then" step of
the `ai-assist` skill. Reply with pass rate, root causes by owner, what was healed and `reports/index.html#rca`.
