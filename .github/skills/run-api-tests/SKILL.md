---
name: run-api-tests
description: Run the Rest Assured + Cucumber + TestNG API tests for an environment and tag filter, then produce RCA and the HTML report. Use when someone asks to run, re-run or smoke-test the APIs.
---

# Run the API tests

## One command (recommended)
```bash
./scripts/run-tests.sh <env> "<tag expression>" [--heal]
./scripts/run-tests.sh qa "@smoke"
./scripts/run-tests.sh qa "@orders and not @flaky"     # @<tag> = an OpenAPI tag of your spec
./scripts/run-tests.sh stage "@P1" --heal              # AI heals automation/test-data failures, then runs once more
```
It runs `mvn test`, then `post-run` (AI RCA + report), prints a one-line summary and exits non-zero if any test failed.
The AI steps use the LLM token when set; otherwise they are queued: answer them with `/ai` (skill `ai-assist`).

## Pieces, when you need them
```bash
mvn test -Denv=qa -Dcucumber.filter.tags="@TC-createOrder-positive-1"   # one case
mvn test -Denv=qa -Dthreads=8                                       # more parallel scenarios
mvn test -Denv=qa -Devidence.all=true                               # evidence for passed tests too
java -jar target/api-test-platform.jar post-run [--heal]            # AI RCA (+ heal) + report from the last run
```

## Before running
- `java -jar target/api-test-platform.jar doctor --env <env>` passes (config, exported secrets, proxy, company CA,
  every base URI reachable). Network failures found here are ENVIRONMENT issues, not test failures: report the
  doctor's hint instead of running the suite.
- `config/env/<env>.properties` exists and its `${VARS}` are set in the shell (e.g. `export QA_API_TOKEN=...`).
- "Missing test data: set testdata.X" means the scenario does not create its data yet: that is what `ai heal` fixes.
- Features exist: `ls src/test/resources/features/generated/` (else `ai automation`, prompt /build-features).

## Outputs
| File | Use |
| --- | --- |
| `reports/index.html#dashboard` | ModernCucumberReporter page; `#rca` for root causes, RCA tab per failed test |
| `target/rca/rca.json`, `target/rca/tickets/*.md` | machine-readable RCA and ready-to-paste bug tickets |
| `target/run-summary.json`, `target/evidence/*.json` | raw results and failure evidence |
| `history/results.jsonl` | run history for flaky / new-failure detection: commit or cache it |

If there are failures, answer the queued rca (and heal) with `/ai` (skill `ai-assist`) when no LLM token is set.
