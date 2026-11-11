# Prompt library

Prompts are code: versioned, reviewed in pull requests, and tested by their consumers.

| File | Used by | Output contract |
| --- | --- | --- |
| `testcases.md` | `ai testcases` / `generate` / Import spec (TestcasesTask), `test-designer` agent | JSON array of test cases (testcase.schema.json) |
| `automation.md` | `ai automation` (AutomationTask), `feature-author` agent | files: feature + `<Tag>Api` + `<Tag>Steps` per tag |
| `rca.md` | `ai rca` (RcaTask), `post-run --llm`, `rca-analyst` agent | one JSON object: entries (category, owner, grouping, fix, ticket) + triage |
| `spec-audit.md` | `spec-analyst` agent | Markdown audit with a verdict |

Format used by the Java `PromptLibrary`: a `## System` section and a `## User` section; `{{name}}` placeholders
are filled at run time. The copy in this folder wins over the one bundled in the jar, so you can tune a prompt
without rebuilding.

Rules for every prompt:
- Inputs are already masked by `Masking`. Never add fields that could carry secrets.
- Ask for a strict, parseable output format and say what to do when unsure ("say unclear", lower confidence).
- The AI decides; the task class in `src/main/java/com/qa/platform/ai/` validates. Keep the answer format here and the validation there in step.
- Change a prompt → bump the `Version` line → note the reason in the PR.
