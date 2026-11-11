# API Test Platform

Turn an API specification or document into human-reviewed Cucumber API tests, execute them with
Rest Assured and TestNG, and investigate failures in an HTML report. The platform is a single
Java 17 Maven project with a CLI, an approval UI, an automation framework, and optional AI assistance.

> **Human approval is required.** AI proposes test cases and writes automation; a reviewer decides
> which cases are approved and reviews generated code, assertions, and root cause analysis (RCA).
> Never weaken a failing assertion just to make a run pass.

## Contents

- [How it works](#how-it-works)
- [Prerequisites and build](#prerequisites-and-build)
- [Quick start: VS Code Copilot agent](#quick-start-vs-code-copilot-agent)
- [Use an LLM API from the UI or CLI](#use-an-llm-api-from-the-ui-or-cli)
- [Approval UI](#approval-ui)
- [AI tasks](#ai-tasks)
- [Environments and company networks](#environments-and-company-networks)
- [Run tests and investigate failures](#run-tests-and-investigate-failures)
- [CLI reference](#cli-reference)
- [Docker and CI](#docker-and-ci)
- [Project structure and extensions](#project-structure-and-extensions)
- [Troubleshooting](#troubleshooting)

## How it works

```text
API spec / doc
   |
   +-- AI spec audit (optional) --> testability gaps and owner questions
   |
   +-- AI test cases -----------> PENDING_REVIEW cases in testcases/
                                      |
                                  human review in UI
                                      |
                                  APPROVED cases
                                      |
                                  AI automation
                                      |
                         feature + <Tag>Api + <Tag>Steps
                                      |
                              review and dry run
                                      |
                           Cucumber / TestNG test run
                                      |
                           evidence + HTML report
                                      |
                        failure collection + optional AI RCA
```

**One AI path, two ways to answer.** Every AI step (UI button, CLI command, after a test run) is an
*AI task*. If an LLM API key is configured on the machine running the platform, the platform calls it
and applies the answer straight away. If not (or if the call fails), the task is **queued** and the
UI shows *Waiting for Copilot*: type `/ai` in VS Code Copilot Chat (Agent mode) and Copilot answers
every queued task with the same prompts; the platform validates the answers exactly as it validates
LLM answers, and the UI refreshes when they are applied. Humans only approve test cases (and review
what the AI wrote).

Cases are stored as JSON under `testcases/`; the UI also stores review events in a database.
Regeneration merges cases for the same spec: new and changed cases are pending review, unchanged
cases retain their status, and cases no longer generated can become obsolete. Reviewer-edited
cases and custom cases receive special protection during merging. Review the resulting changes.

## Prerequisites and build

- JDK 17 and Maven; Maven 3.9+ supports the documented `MAVEN_ARGS` environment variable.
- `bash` and `jq` for the supplied shell scripts; Python 3 for the Copilot guard hook.
- VS Code with Copilot agent mode, and/or an approved LLM API key; Docker for the container setup (optional).
- Network access to the target API is required when running API tests or the environment doctor.

From the project directory:

```bash
mvn -q test -Punit
mvn -q -DskipTests package
```

The first command runs platform unit tests; the second builds `target/api-test-platform.jar`.
Maven may need network access to download dependencies. The repository includes example specs;
replace them or add your own under `specs/`.

## Quick start: VS Code Copilot agent

This path needs Copilot agent mode in VS Code and **no LLM API key**. Every AI step is queued for
Copilot; `/ai` (`.github/prompts/ai.prompt.md`) answers whatever is queued.

1. **Test cases.** In the UI use **Import spec** (or `java -jar target/api-test-platform.jar generate
   --spec specs/openapi.yaml`). The UI shows *Waiting for Copilot*. In Copilot Chat (**Agent** mode)
   type `/ai`. When Copilot has applied its answer, the cases appear in the UI as **PENDING_REVIEW**.
2. **Review.** Approve or reject the cases in the UI (start it as below). This is the human gate.
3. **Automation.** Click **Generate automation (AI)**, then `/ai` again. Review the generated feature,
   `<Tag>Api` and `<Tag>Steps` (`git diff`) and run `mvn -q test -Dcucumber.execution.dry-run=true`.
4. **Run.** **Execute tests** in the UI (or `./scripts/run-tests.sh qa "@pet"`). Failures are queued
   for the AI root cause analysis: `/ai` analyses them and heals automation/test-data faults (for
   example a scenario that needs a pet to exist now creates it), then re-runs once.
   API bugs are never "healed": they stay failures with an owner in `reports/index.html#rca`.

`/pipeline` does the whole flow and stops at the review gate. `java -jar target/api-test-platform.jar
status` always prints what is queued and the next step.

Start the UI with your own reviewer password, kept out of shell history and project files:

```bash
printf 'Choose a local reviewer password: '
read -rs REVIEWER_PASSWORD
echo
export PLATFORM_USERS="reviewer:${REVIEWER_PASSWORD}:REVIEWER"
unset REVIEWER_PASSWORD
./scripts/serve.sh
```

Open [http://localhost:8080](http://localhost:8080) and sign in as `reviewer`. See
[Approval UI](#approval-ui) for roles and security considerations. Edit `config/env/qa.properties`
for your API before running real tests (the checked-in QA example points to the public Petstore).

## Use an LLM API from the UI or CLI

With an LLM API key the platform answers every AI task itself (UI buttons, CLI, after each run,
including heal + re-run) and nothing waits for Copilot. Set the variables in the environment that
starts the UI server or runs the CLI, and restart the UI after changing them. Copilot still works
alongside it: anything the LLM cannot answer (network, quota, outage) is queued for `/ai`.

### Which provider is used

`LLM_PROVIDER` is optional: the platform follows the key you set (`LLM_API_KEY` or `OPENAI_API_KEY`
→ OpenAI-compatible, `ANTHROPIC_API_KEY` → Anthropic, `AZURE_OPENAI_API_KEY` → Azure OpenAI). With
no key, Copilot answers. GitHub Models was retired on July 30, 2026 and is not supported; a
`GITHUB_TOKEN` or the Copilot session is never used as an LLM key. `LLM_ENABLED=false` keeps a key
unused (everything goes to Copilot).

### Company OpenAI-compatible gateway

Obtain an approved gateway base URL, model ID, API key, and authentication-header requirements
from your platform team. Confirm what data may be sent to the provider.

```bash
export LLM_BASE_URL="https://your-approved-gateway.example.com/v1"
export LLM_MODEL="your-model-id"

printf 'LLM API key: '
read -rs LLM_API_KEY
echo
export LLM_API_KEY
```

The client sends requests to `LLM_BASE_URL/chat/completions` using
`Authorization: Bearer <key>` by default. If your gateway expects a different header, set it
before starting the UI, for example `export LLM_AUTH_HEADER=api-key`. For non-Authorization
headers the key is sent without the `Bearer` prefix.

To check the integration using an authorized, non-sensitive spec:

```bash
java -jar target/api-test-platform.jar status          # "AI answers: openai:<model>"
java -jar target/api-test-platform.jar ai spec-audit \
  --spec specs/openapi.yaml --mode llm
```

`--mode llm` forces the API (no Copilot fallback) so you see `[llm]` errors directly. Then set
`PLATFORM_USERS` as shown above and start `./scripts/serve.sh` in the same terminal.

### Other supported client configurations

| Provider | Required settings | Notes |
| --- | --- | --- |
| OpenAI or compatible | `LLM_PROVIDER=openai`, `LLM_API_KEY`, `LLM_MODEL`; set `LLM_BASE_URL` for a gateway | The default base URL is `https://api.openai.com/v1`; `OPENAI_API_KEY` is a fallback key. Verify model availability with your provider. |
| Azure OpenAI | `LLM_PROVIDER=azure-openai`, `LLM_BASE_URL`, `LLM_DEPLOYMENT`, `LLM_API_KEY` | Base URL is the resource URL; optional `LLM_API_VERSION` defaults to `2024-10-21`. `AZURE_OPENAI_API_KEY` is a fallback. |
| Anthropic | `LLM_PROVIDER=anthropic`, `ANTHROPIC_API_KEY`, `LLM_MODEL` | `LLM_API_KEY` is a fallback; optional `LLM_BASE_URL` defaults to `https://api.anthropic.com`. Set an available model explicitly. |

`LLM_PROXY` controls the LLM client's proxy (`none` = direct); otherwise it uses `HTTPS_PROXY` /
`https_proxy`. Hosts in `NO_PROXY` (e.g. `.corp.example.com`) and localhost are called directly. A gateway using a private CA may require a Java trust store. Do not
disable TLS verification to work around certificate errors.

AI inputs are processed by masking code, but masking is not a guarantee that every sensitive
value is detected. Review specs, test cases, evidence, and organizational data-handling rules
before sending anything to an external provider. Never put API keys or passwords in a prompt,
source file, or chat.

## Approval UI

The UI defaults to `127.0.0.1:8080`. `./scripts/serve.sh` requires `PLATFORM_USERS`; direct
`java -jar target/api-test-platform.jar serve` has a local development default
`admin:admin:REVIEWER`. **Set your own credentials and do not expose that default.**
`PLATFORM_USERS` accepts comma-separated `name:password:ROLE` entries, where roles are
`REVIEWER` and `VIEWER`. Use strong passwords, limit who can access the server, and follow
your organization's authentication and deployment policies before sharing it.

| UI action | Behavior |
| --- | --- |
| Import spec | Upload a file or a URL plus optional business context. The AI designs the cases (LLM now, or queued for Copilot `/ai`); they arrive as Pending review. |
| Review cases | Filter and inspect cases, edit JSON, approve, reject, or request changes. Editing returns a case to review. |
| AI assist | Any AI task: test cases, spec audit, automation, RCA, heal. Same behaviour: LLM now, or queued for Copilot. |
| Generate automation (AI) | Feature + `<Tag>Api` + `<Tag>Steps` for the approved cases; review the diff. |
| Execute tests | Environment, tags, threads, **AI root cause analysis** (on by default) and **AI heal + re-run** (LLM key needed; without one `/ai` heals after the run). |
| *Waiting for Copilot* bar | AI tasks queued for Copilot. Type `/ai` in Copilot Chat; **Apply answers** applies answers Copilot wrote but did not apply. The AI chip in the header shows who answers. |
| Open report | Opens the HTML report and its RCA view. |

`VIEWER` access is read-only; reviewers perform changes and start runs. By default review
events use an H2 database at `target/platform/approval-db`. Set `DB_URL`, `DB_USER`, and
`DB_PASSWORD` for a PostgreSQL-backed audit database. Cases remain in files.

## AI tasks

| Task | Input | AI decides | Validated on apply |
| --- | --- | --- | --- |
| `testcases` | `--spec <file>`, optional `--context` | every test case | schema; always PENDING_REVIEW; approvals kept |
| `spec-audit` | `--spec <file>` | testability gaps, owner questions | written to `target/platform/` |
| `automation` | approved cases, optional `--tag` | feature, `<Tag>Api`, `<Tag>Steps`, assertions, data setup | fixed paths/packages; one scenario per approved case with its approved status; no forbidden calls |
| `rca` | the last run | category, owner, grouping, fix, tickets, triage | known categories; counts from evidence |
| `heal` | the last run's TEST_DATA / TEST_DEFECT / unanalysed failures | automation fixes (or "API bug, no change") | as automation: an expectation can never be dropped or changed |

```bash
java -jar target/api-test-platform.jar ai <task> [--spec f] [--tag t]   # LLM now, else queued for Copilot
java -jar target/api-test-platform.jar ai pending                       # what is waiting for Copilot
java -jar target/api-test-platform.jar ai apply                         # apply the answers Copilot wrote
```

`generate --spec <file>` is the same as `ai testcases`. `--mode llm` or `--mode agent` force one way.
Queuing a task again replaces that task's unapplied queue in `target/ai/<task>/`. Prompts are in
`prompts/`; the answer protocol for Copilot is `.github/skills/ai-assist/SKILL.md`. The platform
validates every answer, but validation is not a substitute for human review.

Keep certificate material and local overrides out of source control. Never set `tls.relaxed=true`
to bypass a certificate failure. The doctor checks configuration and reachability; it may make
network requests and cannot prove that every test or permission will succeed.

## Run tests and investigate failures

Generated tests use Cucumber, TestNG and Rest Assured. The UI runs Maven with the selected
environment, tag expression and threads; the shell alternative is:

```bash
./scripts/run-tests.sh qa "@smoke"
# AI heals automation/test-data failures, then the tests run once more:
./scripts/run-tests.sh qa "@smoke" --heal
```

`THREADS=8 ./scripts/run-tests.sh qa "@smoke"` overrides script parallelism. The script
requires generated feature files and `jq`; it runs post-processing after Maven. Inspect the
Maven output, run summary, and report as well as the script exit status, especially after
build failures or when old run artifacts exist. The Maven configuration normally ignores
test failures so post-processing can run; do not treat a bare `mvn test` exit code alone as
proof that scenarios passed.

For direct execution:

```bash
mvn test -Denv=qa -Dcucumber.filter.tags="@smoke" -Dthreads=4
java -jar target/api-test-platform.jar post-run
```

`post-run` runs the AI RCA (LLM, or queued for `/ai`); add `--heal` for the heal task, `--no-ai` to
only collect the facts. The test runner writes evidence to `target/evidence/`, a run summary to
`target/run-summary.json`, and the report to `reports/index.html`. Post-processing collects
failures in `target/rca/` and merges RCA into the report. Open `reports/index.html#rca` or
use **Open report** in the UI.

Scenario case IDs are represented by `@TC-...` tags and connect evidence to cases. Treat
evidence, reports, and reproduced requests as potentially sensitive even where masking is
applied; review before sharing.

## CLI reference

```bash
java -jar target/api-test-platform.jar --help
java -jar target/api-test-platform.jar status
java -jar target/api-test-platform.jar generate --spec specs/openapi.yaml
java -jar target/api-test-platform.jar ai automation --tag pet
java -jar target/api-test-platform.jar ai pending
java -jar target/api-test-platform.jar ai apply
java -jar target/api-test-platform.jar doctor --env qa
java -jar target/api-test-platform.jar post-run
```

| Command | Purpose |
| --- | --- |
| `serve` | Start the approval UI. |
| `generate` | Start AI test-case generation for a local spec or document. |
| `ai <task>` | Run `testcases`, `spec-audit`, `automation`, `rca` or `heal` (LLM, else queued); `ai pending`, `ai apply`. |
| `status` | Cases by status and category, who answers AI tasks, the queue, and the next step. |
| `propose --file <json>` | Import proposed cases as pending review. |
| `doctor --env <name>` | Check environment configuration and reachability. |
| `rca`, `report`, `post-run [--heal]` | AI RCA of the last run, merge it into the report, or both (+ heal). |

`scripts/new-spec.sh <file> [env]` copies a local spec into `specs/`, builds the jar if
missing, and starts generation. Its generation command is allowed to fail without stopping
the script: check its output and complete any agent request before expecting cases. Although
the script accepts a URL argument, the current CLI test-case task expects a local file; use
the UI URL import or download an authorized spec to a local file first.

The `Makefile` provides shortcuts including `build`, `unit`, `serve`, `automation`, `test`, `heal`,
`ai-pending`, `ai-apply` and `report`. Inspect the target before relying on optional arguments.

## Docker and CI

The supplied `docker-compose.yml` runs a UI container with PostgreSQL for audit events and
mounts the workspace. Build the jar first; provide `DB_PASSWORD` and `PLATFORM_USERS` securely
in the Compose environment before running `docker compose up --build`.

The supplied image contains a JRE, **not Maven**, and the Compose file does not pass LLM
settings into the UI container. Consequently, UI test execution and server-side AI require
additional deployment configuration: provide Maven in the container and explicitly pass
approved LLM settings/secrets to it. Setting variables only on the host does not automatically
make them available inside the container. Review port exposure, TLS, credentials and database
security before sharing a deployment.

`.github/workflows/api-tests.yml` builds and unit-tests on pushes and pull requests, has a
PR spec-generation job, and runs committed automation on non-PR events with report artifacts.
The PR job has no configured LLM provider/token, so it can export agent requests rather than
generate cases automatically. If you use the workflows, replace any GitHub Models (retired) LLM
settings with your provider's key as a secret. Configure an approved provider and secrets in the
workflow before relying on server-side AI in CI. The workflow is not updated merely by changing
your local shell. `.github/workflows/copilot-setup-steps.yml` prepares the Copilot cloud-agent
environment.

## Project structure and extensions

| Path | Purpose |
| --- | --- |
| `specs/`, `testcases/` | API inputs and reviewable case JSON. |
| `prompts/` | Versioned AI task prompts. |
| `src/main/java/com/qa/platform/ai/` | AI task export, validation and application. |
| `src/main/java/com/qa/platform/approval/` | Approval API, UI services and test-run orchestration. |
| `src/main/java/com/qa/platform/generate/` | Case storage, merging and proposal import. |
| `src/main/java/com/qa/platform/rca/`, `report/` | Failure collection, history and report merge. |
| `src/test/java/com/qa/automation/` | Client, auth, API, services, steps, assertions and runner. |
| `src/test/resources/features/generated/` | AI-generated feature files for approved cases. |
| `src/test/java/org/rg/reporter/`, `src/test/resources/modern-reporter/ui/` | HTML reporter and its UI. |
| `.github/copilot-instructions.md`, `agents/api-qa`, `skills/`, `prompts/` | Copilot: one agent, `/ai` (answer the queue), `/pipeline`, `/generate-tests`, `/build-features`, `/run-tests`, `/review-status`. |
| `config/env/`, `scripts/` | Environment configuration and local workflow scripts. |

Automation follows **steps -> services/API -> ApiClient -> Rest Assured**. Generated endpoint
and step classes live in `api/generated/` and `steps/generated/`; keep reusable business logic
in hand-written services and steps. For a new auth strategy, add an `AuthProvider` and register
it in `AuthProviders`. For shared steps or report changes, follow the corresponding project
instructions and skills. Review generated content and keep assertions faithful to approved cases.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| UI shows *Waiting for Copilot* | Expected without an LLM key: type `/ai` in Copilot Chat (Agent mode). Or set a key on the server and restart the UI. |
| LLM call fails even though a key is set | The work is queued for `/ai` instead. Check `[llm]` output, base URL, model, auth header, proxy/`NO_PROXY`, CA trust. `ai spec-audit --spec <f> --mode llm` shows the error directly. |
| No approved cases | Have a human review and approve appropriate cases in the UI before generating automation. |
| Copilot answered but nothing changed | Run `ai apply` (or **Apply answers** in the UI) and read the "rejected" lines: they say exactly what to fix. |
| "Missing test data: set testdata.X" | The scenario does not create its data yet: run with **AI heal** (or `ai heal` + `/ai`). |
| Dry run reports undefined or ambiguous steps | Review generated steps and shared definitions; correct the automation and rerun the dry run without weakening assertions. |
| Certificate or connectivity errors | Run `doctor --env <name>`, check proxy and CA configuration, and fix trust rather than disabling TLS verification. |
| RCA is UNCLASSIFIED | The analysis is queued: `/ai` (or set an LLM key and `post-run`). |
| UI/container cannot run Maven | Verify Maven is installed and accessible to the UI process; the supplied Docker image is JRE-only. |
