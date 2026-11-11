---
description: Rules for the versioned LLM prompt library
applyTo: "prompts/**/*.md"
---
- Each prompt has a `Version:` line, a `## System` and a `## User` section, and `{{name}}` placeholders
  that `PromptLibrary` fills. Bump the version on every change and say why in the PR.
- Ask for a strict, parseable output (one JSON object, or one fenced YAML block) and say what to do when unsure.
- Inputs are already masked; never add fields that could carry secrets.
- The AI decides RCA category/owner/grouping (prompts/rca.md) and writes automation (prompts/automation.md);
  the Java side validates the answer. Keep the answer formats in the prompts in step with the task classes in `ai/`.
