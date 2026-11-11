# api-test-platform: instructions for GitHub Copilot

Single-module Maven project (Java 17) that turns Swagger/OpenAPI or API docs into **human-approved**
Cucumber API tests (Rest Assured + TestNG), and explains every failure in an RCA tab of the HTML report.

## Pipeline

```
specs/*.yaml|json|postman.json|md
   │  java -jar target/api-test-platform.jar generate --spec <file|url>
   ▼
testcases/<tag>/<operationId>.json        status = PENDING_REVIEW
   │  HUMAN approves in the UI:  java -jar target/api-test-platform.jar serve  → http://localhost:8080
   ▼
status = APPROVED
   │  java -jar target/api-test-platform.jar features
   ▼
src/test/resources/features/generated/*.feature
   │  mvn test -Denv=qa -Dcucumber.filter.tags="@smoke"
   ▼
target/run-summary.json + target/evidence/<caseId>.json
   │  java -jar target/api-test-platform.jar post-run [--llm]
   ▼
target/rca/rca.json  +  reports/index.html  (ModernCucumberReporter; RCA merged in: #rca view + RCA tab per test)
```

`scripts/run-tests.sh <env> "<tags>"` runs the last three steps and exits with the test result.

## Hard rules (the hook in .github/hooks/guard.json enforces 1-3 in Copilot CLI and cloud agent)

1. **Never approve a test case.** Approval is a human decision made in the UI. Never write `"status": "APPROVED"`,
   never call `/api/testcases/*/approve` or `/api/testcases/bulk`, never edit files under `testcases/` directly.
   To suggest cases, write `testcases/_proposed/<name>.json` and run
   `java -jar target/api-test-platform.jar propose --file testcases/_proposed/<name>.json` (they arrive as PENDING_REVIEW).
2. **Never hand-edit generated code**: `src/test/resources/features/generated/` (regenerate with `features`),
   `src/test/java/com/qa/automation/api/generated/` and `.../steps/generated/` (regenerate with `services --spec`).
3. **Never put secrets in files.** Config uses `${ENV_VAR}`; evidence and reports are masked by `Masking`.
4. **Never change a test, expectation or assertion to make a failing test pass.** A failure is a finding;
   propose the change for human review if the expectation looks wrong.
5. The RCA **category** comes from `com.qa.platform.rca.rules`; an LLM may only rewrite the narrative.
6. Test case ids are stable (`TC-<operationId>-<category>-<detail>`). Never rename them.

## Build and test

- Build: `mvn -q -DskipTests package` → `target/api-test-platform.jar`
- Platform unit tests (no network): `mvn -q test -Punit`
- API tests: `mvn test -Denv=qa -Dcucumber.filter.tags="@smoke"`; one case: `-Dcucumber.filter.tags="@TC-<operationId>-positive-1"`
- Dry run: `mvn test -Dcucumber.execution.dry-run=true`
- AI tasks (doc-to-openapi, spec-audit, testcase-augment, rca-narrative, triage-summary): `java -jar target/api-test-platform.jar ai <task> ...`.
  Without an LLM token you are the model: `--mode agent`, answer `target/ai/<task>/requests.md`, then `--apply --by copilot-agent`
  (skill `ai-assist`). Never write their result files yourself.
- Check an environment before running (company proxy, CA, secrets, reachability): `java -jar target/api-test-platform.jar doctor --env <env>`
- Company network settings live in `config/env/<env>.properties` (`http.proxy.*`, `tls.*`, `http.header.*`);
  never set `tls.relaxed=true` to get past a certificate error, never commit files from `config/certs/`.
- Endpoint classes and step definitions from a spec: `java -jar target/api-test-platform.jar services --spec specs/<spec>`
  (`api/generated/<Tag>Api`, `steps/generated/<Tag>Steps`; generated features call `When I call the <tag> operation "<id>"`)

## Where things are

| Path | What |
| --- | --- |
| `src/main/java/com/qa/platform/ingest` | SpecLoader (swagger-parser), ApiModelMapper, Postman + Markdown adapters |
| `src/main/java/com/qa/platform/generate` | ValueGenerator, CaseFactory, `rules/` (one class per category), CaseStore, CaseDiffer |
| `src/main/java/com/qa/platform/approval` | Spring Boot approval API, security, audit trail |
| `src/main/java/com/qa/platform/feature` | FeatureWriter (approved JSON → Gherkin) |
| `src/main/java/com/qa/platform/rca` | RcaEngine, `rules/` (one class per failure category), LLM enricher, bug tickets |
| `src/main/java/com/qa/platform/report` | ReportRcaMerger: adds rca.json into reports/index.html |
| `src/main/java/com/qa/platform/codegen` | ServiceGenerator (`<Tag>Api`), StepGenerator (`<Tag>Steps`), CodegenService |
| `src/test/java/org/rg/reporter`, `src/test/resources/modern-reporter/ui` | the report plugin and its UI (RCA view in app.js) |
| `src/test/java/com/qa/automation/client` | `ApiClient` (the only Rest Assured user), `ApiRequest`/`ApiResponse`, auth strategies |
| `src/test/java/com/qa/automation/api` | `BaseApi`; `api/generated/<Tag>Api` written by the `services` command |
| `src/test/java/com/qa/automation/services` | `ServiceFactory`, `OperationService` (runs JSON cases), and the domain services you add (`<Domain>Service`) |
| `src/test/java/com/qa/automation/steps` | `ApiSteps` (shared Given/Then), `generated/<Tag>Steps` (one When per operation, from `services`), and the domain steps you add (`<Domain>Steps`) |
| `src/test/java/com/qa/automation` (other) | runner, hooks (evidence + cleanup), models, assertions, retry, run-summary plugin |
| `config/env/*.properties` | base URIs, auth profiles, `testdata.*` |
| `prompts/` | versioned LLM prompts used by the Java code and the custom agents |
| `.github/agents`, `.github/skills`, `.github/prompts`, `.github/instructions`, `.github/hooks` | the Copilot layer |

## Code style

Java 17 records for data, no Lombok. Automation follows Steps → Services → Api → ApiClient (see
`.github/instructions/automation.instructions.md`). One rule per class for generation and RCA. Steps stay generic:
a new API must never need new step code. Every new rule gets a TestNG unit test in `src/test/java/com/qa/platform`.
