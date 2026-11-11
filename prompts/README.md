# Prompt library

Every AI task of the platform has one prompt here. The same prompt is used whoever answers: the LLM API
(when a token is set) or Copilot agent mode (the request is queued in `target/ai/<task>/requests.md`, `/ai` answers it).

| File | Task (CLI / UI) | Output contract (validated by the task class) |
| --- | --- | --- |
| `testcases.md` | `ai testcases` = `generate` = Import spec | JSON array of test cases (testcase.schema.json), stored as PENDING_REVIEW |
| `spec-audit.md` | `ai spec-audit` = AI assist "Audit spec" | Markdown audit with a verdict |
| `automation.md` | `ai automation` = UI button 1 | files: feature + `<Tag>Api` + `<Tag>Steps` per tag; one scenario per approved case, approved status stated |
| `rca.md` | `ai rca` = after every run | one JSON object: entries (category, owner, grouping, fix, ticket) + triage |
| `heal.md` | `ai heal` = Execute "AI heal" | same files as automation, only for TEST_DATA/TEST_DEFECT failures; expectations never change |

Format used by the Java `PromptLibrary`: a `## System` section and a `## User` section; `{{name}}` placeholders
are filled at run time. The copy in this folder wins over the one bundled in the jar, so you can tune a prompt
without rebuilding.

Rules for every prompt:
- Inputs are already masked by `Masking`. Never add fields that could carry secrets.
- Ask for a strict, parseable output format and say what to do when unsure ("say unclear", lower confidence).
- The AI decides; the task class in `src/main/java/com/qa/platform/ai/` validates. Keep the answer format here and the validation there in step.
- Change a prompt → bump the `Version` line → note the reason in the PR.
