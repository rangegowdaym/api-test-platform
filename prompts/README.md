# Prompt library

Prompts are code: versioned, reviewed in pull requests, and tested by their consumers.

| File | Used by | Output contract |
| --- | --- | --- |
| `rca-analysis.md` | `LlmRcaEnricher` (Java, `post-run --llm`) and the `rca-analyst` agent | one JSON object: summary, probableCause, suggestedFix, confidence |
| `doc-to-openapi.md` | `MarkdownAdapter` fallback (Java) | one OpenAPI 3.0 YAML document in a ```yaml block |
| `testcase-augment.md` | `test-designer` agent | JSON array of test cases matching `schemas/testcase.schema.json` |
| `spec-audit.md` | `spec-analyst` agent | Markdown audit with a verdict |
| `triage-summary.md` | `rca-analyst` agent | Markdown triage note |
| `bug-ticket.md` | `rca-analyst` agent (rewrite of generated tickets) | Markdown ticket |

Format used by the Java `PromptLibrary`: a `## System` section and a `## User` section; `{{name}}` placeholders
are filled at run time. The copy in this folder wins over the one bundled in the jar, so you can tune a prompt
without rebuilding.

Rules for every prompt:
- Inputs are already masked by `Masking`. Never add fields that could carry secrets.
- Ask for a strict, parseable output format and say what to do when unsure ("say unclear", lower confidence).
- The rule engine's category is authoritative; prompts may explain it, not overrule it.
- Change a prompt → bump the `Version` line → note the reason in the PR.
