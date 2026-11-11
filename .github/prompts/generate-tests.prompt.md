---
description: Audit a spec or API doc, then generate JSON test cases for human review
agent: spec-analyst
argument-hint: "spec path or URL, e.g. specs/orders.yaml"
---
Audit `${input:spec:specs/your-service.yaml}` for testability and write `target/platform/spec-audit.md`.
Follow the `swagger-to-testcases` skill for the commands.

When the verdict is not NOT READY, use the **Design test cases** handoff (or switch to the `test-designer` agent)
to generate the cases and propose business cases. Never approve anything.
