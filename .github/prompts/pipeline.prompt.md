---
description: Take a spec all the way to a report, stopping at the human approval gate
agent: pipeline-orchestrator
argument-hint: "spec path or URL, env, tags"
---
Spec: `${input:spec:specs/your-service.yaml}`. Environment: `${input:env:qa}`. Tags: `${input:tags:@smoke}`.
Stop and wait at the human approval step.
