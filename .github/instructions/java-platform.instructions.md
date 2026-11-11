---
description: Conventions for the platform code (AI tasks, case store, approval, runs, report)
applyTo: "src/main/java/**/*.java"
---
- Data types are Java records in `com.qa.platform.model`; add `with...` copy methods instead of setters.
- Use `com.qa.platform.common.Json.MAPPER`; a YAML mapper is fine for reading specs (`SpecText`).
- A new test category = add it to `prompts/testcases.md` and the `category` enum in `testcase.schema.json` (the AI designs the cases).
- A new failure category = add it to `prompts/rca.md`, `RcaTask.CATEGORIES` and `rca.schema.json` (the AI decides).
- Anything written to evidence, reports or sent to an LLM goes through `Masking`.
- AI work is an `ai/AiTask` (prompt + validation in apply), run by `AiRunner` with an LLM API or the IDE agent.
  Keep Java to validation and file writing; let the prompt carry the judgement.
