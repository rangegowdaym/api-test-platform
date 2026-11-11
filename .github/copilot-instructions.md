# api-test-platform: instructions for GitHub Copilot

Single-module Maven project (Java 17) that turns Swagger/OpenAPI or API docs into **human-approved** Cucumber API
tests (Rest Assured + TestNG) and explains every failure in the RCA view of the HTML report.

**AI does everything except approval:** the AI (you, or an LLM API) designs the test cases from the spec, writes the
feature files, `<Tag>Api` and `<Tag>Steps` with their assertions, and does the root cause analysis (category, owner,
grouping, fix). Humans approve every test case and review what the AI wrote (git diff, the report).

## Pipeline

```
specs/*.yaml|json|postman.json|md
   │  ai testcases --spec <file>   (AI: positive, negative, boundary, auth, business cases)
   ▼
testcases/<tag>/<operationId>.json   PENDING_REVIEW  ──►  HUMAN approves in the UI (serve → localhost:8080)
   │  ai automation   (AI: features + <Tag>Api + <Tag>Steps + assertions for the APPROVED cases)
   ▼
src/test/resources/features/generated/<tag>.feature, api/generated/, steps/generated/   ──► testers review the diff
   │  mvn test -Denv=qa -Dcucumber.filter.tags="@smoke"     (or ./scripts/run-tests.sh qa "@smoke")
   ▼
target/evidence/<caseId>.json  ──►  post-run  ──►  ai rca  (AI: category, owner, grouping, fix, tickets, triage)
   ▼
target/rca/rca.json + reports/index.html (#rca)   ──► testers review
```

## AI tasks: `java -jar target/api-test-platform.jar ai <task> [args] [--mode auto|llm|agent]`

| Task | You produce | Check |
| --- | --- | --- |
| `automation [--tag t]` | the 3 files per tag (see prompts/automation.md) | `mvn -q test -Dcucumber.execution.dry-run=true` |
| `rca` | rca entries + triage (prompts/rca.md) | report RCA view |
| `testcases --spec s [--context "..."]` | all test cases for the spec → PENDING_REVIEW | approval UI |
| `spec-audit --spec s` | gaps and questions for the API owner | read it |

Without an LLM token YOU are the model: `--mode agent`, answer `target/ai/<task>/requests.md` (files go into the
answer folder it names), then `ai <task> --apply --by copilot-agent`. `--apply` validates (paths, packages, approved
case tags, categories) and writes the result. Skill: `ai-assist`.

## Hard rules

1. **Never approve a test case** (no `"status": "APPROVED"`, no approve endpoints, no edits in `testcases/`).
   Approval is the human gate. Cases arrive through `ai testcases` or `propose` (always PENDING_REVIEW).
2. **Never put secrets in files.** Config uses `${ENV_VAR}`; everything sent to an AI is masked first.
3. **Never weaken an assertion to make a failing test pass.** A failure is a finding: report it (RCA). If an
   expectation contradicts the spec, say so (category TEST_DEFECT) and let a human decide.
4. Write `rca.json` and the report only through `ai rca --apply` (the guard hook blocks direct edits).
5. Test case ids are stable (`TC-<tag>-<operationId>-<category>-<detail>`); scenarios are tagged `@<id>`.

## Build and check

- Build: `mvn -q -DskipTests package`; platform unit tests: `mvn -q test -Punit`
- After AI automation: `mvn -q test -Dcucumber.execution.dry-run=true` must pass (compiles + every step binds)
- Run: `./scripts/run-tests.sh <env> "<tags>"`; environment check: `java -jar target/api-test-platform.jar doctor --env <env>`
- Company network: `config/env/<env>.properties` (`http.proxy.*`, `tls.*`, `http.header.*`); never `tls.relaxed=true`
  to get past a certificate error; never commit `config/certs/`.

## Where things are

| Path | What |
| --- | --- |
| `src/main/java/com/qa/platform/generate` | CaseStore, CaseDiffer (keeps approvals on re-import), ProposalImporter |
| `src/main/java/com/qa/platform/approval` | approval UI + REST API, pipeline buttons, test runs |
| `src/main/java/com/qa/platform/ai` | AI tasks (testcases, automation, rca, spec-audit), AiRunner |
| `src/main/java/com/qa/platform/rca`, `report` | failure collection + history; RCA merge into reports/index.html |
| `src/test/java/com/qa/automation/client` | `ApiClient` (the only Rest Assured user), `ApiRequest`/`ApiResponse`, auth |
| `src/test/java/com/qa/automation/{api,services,steps,assertions,context}` | framework the AI builds on: `BaseApi`, `ServiceFactory`, `OperationService.prepare`, `ApiSteps`, `ResponseAssert`, `ScenarioContext` |
| `.../api/generated`, `.../steps/generated`, `src/test/resources/features/generated` | written by `ai automation` |
| `src/test/java/org/rg/reporter`, `src/test/resources/modern-reporter/ui` | report plugin + UI (RCA view) |
| `prompts/` | the prompts of the AI tasks; `config/env/` environments |

## Code style

Java 17 records, no Lombok. Steps → Services/Api → ApiClient. Generated Steps: one public constructor
(ScenarioContext, ServiceFactory); reuse `ApiSteps` steps instead of redefining them.
