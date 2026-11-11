---
description: Audit a spec and let the AI design all its test cases for human review
agent: test-designer
---
For `${input:spec:specs/your-api.yaml}`: first follow the `spec-analyst` steps (ai spec-audit), then the
`swagger-to-testcases` skill (ai testcases, with business context `${input:context:none}`). Reply with the audit
verdict, counts per category and tag, the assumptions to check, and the review link http://localhost:8080.
