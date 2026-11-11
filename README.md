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
- [AI tasks and agent mode](#ai-tasks-and-agent-mode)
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

The CLI can use either an IDE agent or an LLM API. The browser UI does **not** call the VS Code
Copilot session: its AI buttons call an LLM API configured on the server. If no server-side LLM
is configured, use Copilot agent mode separately for AI tasks and return to the UI for review
and execution.

Cases are stored as JSON under `testcases/`; the UI also stores review events in a database.
Regeneration merges cases for the same spec: new and changed cases are pending review, unchanged
cases retain their status, and cases no longer generated can become obsolete. Reviewer-edited
cases and custom cases receive special protection during merging. Review the resulting changes.

## Prerequisites and build

- JDK 17 and Maven; Maven 3.9+ supports the documented `MAVEN_ARGS` environment variable.
- `bash` and `jq` for the supplied shell scripts; Python 3 for the Copilot guard hook.
- Optional: VS Code with Copilot agent mode, or an approved LLM API; Docker for the container setup.
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

This path needs a configured Copilot agent in VS Code but **no separate platform LLM API key**.
Commands below use the included `specs/openapi.yaml` as an example; use your own spec and an
authorized test environment for real work.

### 1. Audit and design cases

In Copilot Chat, select **Agent** mode and ask:

```text
Use the ai-assist workflow to audit specs/openapi.yaml for testability.
```

Review the audit in `target/platform/spec-audit-openapi.yaml.md`, resolve important questions
with the API owner, and then ask:

```text
Use the ai-assist workflow to design test cases for specs/openapi.yaml.
```

The `/ai` prompt in `.github/prompts/ai.prompt.md` can also be used to select `spec-audit` or
`testcases` and supply `--spec specs/openapi.yaml`. The agent exports requests, answers them,
and applies validated results. New cases are **PENDING_REVIEW**, not approved.

### 2. Start the UI and review cases

Set a local reviewer password without placing it in shell history or a project file:

```bash
printf 'Choose a local reviewer password: '
read -rs REVIEWER_PASSWORD
echo
export PLATFORM_USERS="reviewer:${REVIEWER_PASSWORD}:REVIEWER"
unset REVIEWER_PASSWORD
./scripts/serve.sh
```

Open [http://localhost:8080](http://localhost:8080), sign in as `reviewer`, and review the
pending cases. A human must approve the cases to automate. See [Approval UI](#approval-ui)
for roles and security considerations.

### 3. Generate and review automation

In Copilot Chat, ask:

```text
Use the ai-assist workflow to generate automation for the approved test cases.
```

Alternatively, use `/ai` with task `automation`, optionally specifying `--tag pet`. The agent
generates the feature, API class, and step class through the platform's agent apply workflow.
Review all generated files and assertions; in a Git checkout, use `git diff` as well. Then run:

```bash
mvn -q test -Dcucumber.execution.dry-run=true
```

The dry run compiles the automation and checks that steps bind; it does not prove that API
responses or assertions are correct.

### 4. Configure and run

Edit `config/env/qa.properties` for the actual API URL, auth profile, and required test data.
The checked-in QA example points to the public Petstore, so do not run it unintentionally.
Use environment variables for real secrets.

```bash
java -jar target/api-test-platform.jar doctor --env qa
./scripts/run-tests.sh qa "@smoke"
```

Check `reports/index.html` and the run output. If there are failures, ask the agent to use the
`ai-assist` RCA workflow, or use `/rca`; review its conclusions against the evidence. The UI can
also run the tests and open the report.

## Use an LLM API from the UI or CLI

Use this route if an approved server-side LLM should handle the UI's **Import spec**,
**AI assist**, **Generate automation (AI)**, or optional AI RCA. Set the variables in the
environment that starts the UI server; the browser does not pass its Copilot credentials to it.
Restart the UI after changing the server environment.

### Important: GitHub Models retirement

The current code defaults `LLM_PROVIDER` to `github-models` and can read `GITHUB_MODELS_TOKEN`
or `GITHUB_TOKEN`, but [GitHub Models was retired on July 30, 2026](https://docs.github.com/en/github-models).
Those settings do not make the retired inference API available. A GitHub Copilot subscription or
VS Code session token is not a substitute for an LLM API key. Explicitly choose a supported
provider or use agent mode.

### Company OpenAI-compatible gateway

Obtain an approved gateway base URL, model ID, API key, and authentication-header requirements
from your platform team. Confirm what data may be sent to the provider.

```bash
export LLM_PROVIDER=openai
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
java -jar target/api-test-platform.jar ai spec-audit \
  --spec specs/openapi.yaml --mode llm
```

This performs an external API call and writes an audit if accepted. `--mode llm` requires a
configured key; inspect `[llm]` errors and rejected results rather than assuming success. Then
set `PLATFORM_USERS` as shown above and start `./scripts/serve.sh` in the same terminal.

### Other supported client configurations

| Provider | Required settings | Notes |
| --- | --- | --- |
| OpenAI or compatible | `LLM_PROVIDER=openai`, `LLM_API_KEY`, `LLM_MODEL`; set `LLM_BASE_URL` for a gateway | The default base URL is `https://api.openai.com/v1`; `OPENAI_API_KEY` is a fallback key. Verify model availability with your provider. |
| Azure OpenAI | `LLM_PROVIDER=azure-openai`, `LLM_BASE_URL`, `LLM_DEPLOYMENT`, `LLM_API_KEY` | Base URL is the resource URL; optional `LLM_API_VERSION` defaults to `2024-10-21`. `AZURE_OPENAI_API_KEY` is a fallback. |
| Anthropic | `LLM_PROVIDER=anthropic`, `ANTHROPIC_API_KEY`, `LLM_MODEL` | `LLM_API_KEY` is a fallback; optional `LLM_BASE_URL` defaults to `https://api.anthropic.com`. Set an available model explicitly. |

`LLM_PROXY` controls the LLM client's proxy; otherwise it checks `HTTPS_PROXY` / `https_proxy`
and then the JVM default. A gateway using a private CA may require a Java trust store. Do not
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
| Import spec | Upload a supported file or supply a URL and optional context. With a server LLM, it proposes cases. Without one, the saved spec and agent-mode instructions are returned instead. |
| Review cases | Filter and inspect cases, edit JSON, approve, reject, or request changes. Editing returns a case to review. |
| AI assist | Runs supported test-case, spec-audit, and RCA tasks using the server LLM; without a token it provides agent-mode guidance. |
| Generate automation (AI) | Requires approved cases and a configured server LLM. Writes automation for review; it does not invoke VS Code Copilot. |
| Execute tests | Select environment, tags and thread count; optionally check the environment and enable server-side AI RCA. |
| Open report | Opens the HTML report and its RCA view. |

`VIEWER` access is read-only; reviewers perform changes and start runs. By default review
events use an H2 database at `target/platform/approval-db`. Set `DB_URL`, `DB_USER`, and
`DB_PASSWORD` for a PostgreSQL-backed audit database. Cases remain in files.

## AI tasks and agent mode

| Task | Input | Result |
| --- | --- | --- |
| `spec-audit` | `--spec <file>` | Testability audit under `target/platform/`. |
| `testcases` | `--spec <file>`, optional `--context` | Validated cases merged into `testcases/` for human review. |
| `automation` | Approved cases, optional `--tag <tag>` | Generated feature, API and steps files per tag; review and dry-run them. |
| `rca` | Failure evidence and collected RCA from a run | AI analysis, optional triage/tickets, and an updated report. |

`generate --spec <file>` is an alias for the AI test-case task. `--mode auto` selects an LLM
when the configured provider has a token, otherwise exports for an agent; a token's presence
does not prove the provider is reachable. A failed LLM call does not automatically invoke an
IDE agent. Use `--mode agent` to force the IDE workflow, even if an old token is present.

Exporting a task again replaces that task's `target/ai/<task>/` directory, including any
unapplied answers. Apply or preserve work in progress before starting another export of the
same task.

For manual agent-mode orchestration:

```bash
java -jar target/api-test-platform.jar ai spec-audit \
  --spec specs/openapi.yaml --mode agent
```

1. Read `target/ai/spec-audit/requests.md`.
2. Have the agent answer **each** request in the answer path named there. Automation answers
   are folders containing the requested files rather than a single text answer.
3. Apply and inspect the result:

   ```bash
   java -jar target/api-test-platform.jar ai spec-audit --apply --by copilot-agent
   ```

4. Fix rejected answers and apply again. Use the same export/answer/apply pattern for
   `testcases`, `automation`, and `rca`; see `.github/skills/ai-assist/SKILL.md`.

For RCA, run tests and post-run collection first. AI analysis may leave uncovered failures
`UNCLASSIFIED`; humans should verify all conclusions. The platform validates outputs but
validation is not a substitute for human review.

## Environments and company networks

Environment files are `config/env/<name>.properties`; `qa` is the default. Set `baseUri`,
optional `baseUri.<tag>`, auth profiles and `testdata.*` values for your API. Local-only
overrides can go in the git-ignored `config/env/<name>.local.properties`.

```bash
./scripts/new-env.sh sit       # copies qa; does not overwrite an existing sit file
# Edit config/env/sit.properties and export the environment variables it references.
java -jar target/api-test-platform.jar doctor --env sit
```

Use `${ENV_VAR}` references in configuration rather than committing secrets. The shipped
`qa.properties` is a Petstore example, not your company's environment.

| Need | Configuration |
| --- | --- |
| HTTP proxy for API calls | `http.proxy.host`, `http.proxy.port`, optional credentials and `http.proxy.nonProxyHosts` in the environment file. |
| Company CA or mutual TLS for API calls | `tls.trustStore` / `tls.keyStore` and environment-backed passwords; see `config/certs/README.md`. |
| Gateway or tracing headers | `http.header.<name>`; use environment variables for sensitive values. |
| Corporate Maven repository | `config/maven/settings-company.xml`, appropriate environment variables, and Maven settings arguments; the UI also supports `PLATFORM_MVN_ARGS`. |
| LLM proxy / trust | `LLM_PROXY` or `HTTPS_PROXY`, plus Java trust-store settings if needed; LLM networking is separate from API-test settings. |

Keep certificate material and local overrides out of source control. Never set `tls.relaxed=true`
to bypass a certificate failure. The doctor checks configuration and reachability; it may make
network requests and cannot prove that every test or permission will succeed.

## Run tests and investigate failures

Generated tests use Cucumber, TestNG and Rest Assured. The UI runs Maven with the selected
environment, tag expression and threads; the shell alternative is:

```bash
./scripts/run-tests.sh qa "@smoke"
# Optional server-side LLM RCA:
./scripts/run-tests.sh qa "@smoke" --llm
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

Add `--llm` to `post-run` for configured API-based RCA, or use agent-mode `rca` after
post-run. The test runner writes evidence to `target/evidence/`, a run summary to
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
java -jar target/api-test-platform.jar generate --spec specs/openapi.yaml --mode agent
java -jar target/api-test-platform.jar ai automation --tag pet --mode agent
java -jar target/api-test-platform.jar doctor --env qa
java -jar target/api-test-platform.jar post-run
```

| Command | Purpose |
| --- | --- |
| `serve` | Start the approval UI. |
| `generate` | Start AI test-case generation for a local spec or document. |
| `ai <task>` | Run or export `testcases`, `spec-audit`, `automation`, or `rca`; `--apply` applies exported agent answers. |
| `status` | Count cases by status and category. |
| `propose --file <json>` | Import proposed cases as pending review. |
| `doctor --env <name>` | Check environment configuration and reachability. |
| `rca`, `report`, `post-run` | Collect failures, merge RCA into the report, or do both. |

`scripts/new-spec.sh <file> [env]` copies a local spec into `specs/`, builds the jar if
missing, and starts generation. Its generation command is allowed to fail without stopping
the script: check its output and complete any agent request before expecting cases. Although
the script accepts a URL argument, the current CLI test-case task expects a local file; use
the UI URL import or download an authorized spec to a local file first.

The `Makefile` provides shortcuts including `build`, `unit`, `serve`, `automation`, `test`,
and `report`. Inspect the target before relying on optional arguments.

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
generate cases automatically. The API-test job currently hard-codes the retired GitHub Models
provider; do not enable its LLM RCA as-is. Configure an approved provider and secrets in the
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
| `.github/agents/`, `.github/skills/`, `.github/prompts/` | Project-specific Copilot agents, skills and prompt files. |
| `config/env/`, `scripts/` | Environment configuration and local workflow scripts. |

Automation follows **steps -> services/API -> ApiClient -> Rest Assured**. Generated endpoint
and step classes live in `api/generated/` and `steps/generated/`; keep reusable business logic
in hand-written services and steps. For a new auth strategy, add an `AuthProvider` and register
it in `AuthProviders`. For shared steps or report changes, follow the corresponding project
instructions and skills. Review generated content and keep assertions faithful to approved cases.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| UI says no LLM token | Set a supported provider and its key in the server's environment, restart the UI, or use Copilot agent mode. The UI does not use the Copilot session token. |
| LLM call fails even though a token is present | Check `[llm]` output, provider URL, model, permissions, proxy, CA trust and company policy. Token presence is not a connectivity check. |
| No approved cases | Have a human review and approve appropriate cases in the UI before generating automation. |
| Agent export exists but no cases or code appear | Complete every answer in the exported requests file and run the matching `ai <task> --apply`; inspect rejections. |
| Dry run reports undefined or ambiguous steps | Review generated steps and shared definitions; correct the automation and rerun the dry run without weakening assertions. |
| Certificate or connectivity errors | Run `doctor --env <name>`, check proxy and CA configuration, and fix trust rather than disabling TLS verification. |
| RCA is unclassified | Run post-processing first; then use configured LLM RCA or the agent RCA workflow, and review the evidence and any rejected answers. |
| UI/container cannot run Maven | Verify Maven is installed and accessible to the UI process; the supplied Docker image is JRE-only. |
