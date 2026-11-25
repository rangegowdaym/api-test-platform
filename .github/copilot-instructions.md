# api-test-platform: instructions for GitHub Copilot

Java 17 Maven project: an API spec becomes **human-approved** Cucumber API tests (Rest Assured + TestNG), and every
failure is explained in the report's RCA view. **The AI does everything except approving test cases.**

## One AI path, two ways to answer

Every AI job is an *AI task* run by `java -jar target/api-test-platform.jar ai <task>` (also behind every UI button):

- **LLM API key configured** (`LLM_API_KEY` + `LLM_BASE_URL` for a company gateway, or `ANTHROPIC_API_KEY`...): the platform
  calls the LLM API itself and applies the answer. Nothing for you to do.
- **No token**: the task is **queued** in `target/ai/<task>/requests.md` and **you are the model**. The person types
  `/ai`; you answer every queued request and run `ai apply`. Skill: `ai-assist`.

Either way the answer goes through the same validation in `src/main/java/com/qa/platform/ai/` before anything changes.

| Task | AI decides | Prompt | Validation on apply |
| --- | --- | --- | --- |
| `testcases --spec s [--context ".."]` | every test case for the spec | prompts/testcases.md | schema; forced PENDING_REVIEW; approvals kept |
| `spec-audit --spec s` | gaps, questions for the API owner | prompts/spec-audit.md | markdown |
| `automation [--tag t]` | feature + `<Tag>Api` + `<Tag>Steps` + assertions + test data setup | prompts/automation.md | fixed paths/packages; one scenario per APPROVED case, approved status stated; no forbidden calls |
| `rca` | category, owner, grouping, fix, tickets, triage | prompts/rca.md | known categories; facts from evidence |
| `heal [--tag t]` | fixes automation/test-data failures (TEST_DATA, TEST_DEFECT, unanalysed) | prompts/heal.md | same as automation: expectations can't change |

## Flow

```
specs/<file>  → ai testcases → testcases/ PENDING_REVIEW → HUMAN approves (serve → localhost:8080)
              → ai automation → features/generated + api/generated + steps/generated
              → mvn test (UI "Execute tests" / scripts/run-tests.sh) → ai rca → ai heal → re-run → reports/index.html#rca
```
`java -jar target/api-test-platform.jar status` prints the queue and the one next step.

## Hard rules

1. **Never approve a test case** (no `"status": "APPROVED"`, no approve endpoints, no edits in `testcases/`).
2. **Never put secrets in files.** Config uses `${ENV_VAR}`; everything sent to an AI is masked first.
3. **Never weaken, remove or skip an expectation to make a test pass.** A wrong API is a finding (RCA), not a fix.
   If an expectation contradicts the spec, report TEST_DEFECT and let a human decide.
4. Generated code, `rca.json` and the report change only through `ai ... apply` (the guard hook blocks direct edits).
5. Test case ids are stable (`TC-<tag>-<operationId>-<category>-<detail>`); scenarios are tagged `@<id>`.
6. Scenarios are self-sufficient: they create the data they need (ctx.vars + cleanup), never depend on each other.

## Build and check

- Build: `mvn -q -DskipTests package`; platform unit tests: `mvn -q test -Punit`
- After automation/heal: `mvn -q test -Dcucumber.execution.dry-run=true` must pass (compiles, every step binds)
- Run: `./scripts/run-tests.sh <env> "<tags>"`; environment check: `java -jar target/api-test-platform.jar doctor --env <env>`
- Company network: `config/env/<env>.properties` (`http.proxy.*`, `tls.*`, `http.header.*`); never `tls.relaxed=true`
  to get past a certificate error; never commit `config/certs/`.

## Where things are

| Path | What |
| --- | --- |
| `src/main/java/com/qa/platform/ai` | the AI tasks + `AiRunner` (LLM or queue, `pending`, `apply`) |
| `src/main/java/com/qa/platform/approval` | approval UI + REST API (`/api/ai/*`), test runs |
| `src/main/java/com/qa/platform/generate`, `rca`, `report` | case store (keeps approvals), failure facts + history, RCA in the report |
| `src/test/java/com/qa/automation/client` | `ApiClient` (the only Rest Assured user), `ApiRequest`/`ApiResponse`, auth |
| `src/test/java/com/qa/automation/{api,services,steps,assertions,context}` | framework the AI builds on: `BaseApi`, `ServiceFactory`, `OperationService.prepare`, `ApiSteps`, `ResponseAssert`, `ScenarioContext` |
| `.../api/generated`, `.../steps/generated`, `src/test/resources/features/generated` | written only by `ai automation` / `ai heal` |
| `prompts/` | one prompt per AI task; `config/env/` environments |

Java 17 records, no Lombok. Steps → Api → ApiClient. Generated Steps: one public constructor
(ScenarioContext, ServiceFactory); reuse `ApiSteps` steps instead of redefining them.
