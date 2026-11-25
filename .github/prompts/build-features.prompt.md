---
description: Let the AI write the automation (features, Api, Steps, assertions) for the APPROVED cases
agent: api-qa
---
Tag `${input:tag:all tags}`. Run `ai automation` (with `--tag <tag>` unless "all tags"), answer + apply when queued
(`ai-assist` skill), then `mvn -q test -Dcucumber.execution.dry-run=true` must pass. Reply with the files written and
"review the diff before running".
