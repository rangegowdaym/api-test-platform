---
description: Rules for the versioned LLM prompt library
applyTo: "prompts/**/*.md"
---
- Each prompt has a `Version:` line, a `## System` and a `## User` section, and `{{name}}` placeholders
  that `PromptLibrary` fills. Bump the version on every change and say why in the PR.
- Ask for a strict, parseable output (one JSON object, or one fenced YAML block) and say what to do when unsure.
- Inputs are already masked; never add fields that could carry secrets.
- The rule engine's RCA category is authoritative; prompts may explain it, never overrule it.
