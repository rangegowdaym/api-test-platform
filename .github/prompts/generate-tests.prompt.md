---
description: Let the AI audit a spec and design all its test cases for human review
agent: api-qa
---
Spec `${input:spec:specs/your-api.yaml}`, business context `${input:context:none}`.
Run `ai spec-audit --spec <spec>` and `ai testcases --spec <spec> --context "<context>"`; answer + apply when queued
(`ai-assist` skill). Reply with the audit verdict, counts per category and tag, assumptions to check, and
"review at http://localhost:8080".
