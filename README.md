# api-test-platform

Swagger/OpenAPI or API docs → JSON test cases → **human approval** → Cucumber features →
Rest Assured + TestNG automation → **RCA for every failure** in your Modern Cucumber report, with an RCA view.

One Maven module, one runnable jar, plus a **GitHub Copilot** layer: repository and path instructions,
custom agents, agent skills, prompt files (slash commands), a guard hook and the cloud agent setup workflow.
Works in VS Code (Copilot Chat, agent mode), GitHub Copilot CLI and the Copilot cloud agent on GitHub.com.

---

## 0. Quick start with ANY Swagger file

```bash
mvn -q package -Punit
./scripts/new-spec.sh /path/to/your-api.yaml            # copy → read → <Tag>Api + <Tag>Steps → rebuild → JSON cases
#   prints the config keys still missing: baseUri, auth.default.*, testdata.<pathId>
vi config/env/qa.properties && export QA_API_TOKEN=...
PLATFORM_USERS="you:pass:REVIEWER" ./scripts/serve.sh   # http://localhost:8080: approve, then the 4 pipeline buttons
```

In IntelliJ, select the shared **Unit tests and package** run configuration.
For an existing Maven configuration, replace its **Run** field with `-q package -Punit`.
Do not paste shell operators (`&&`), another `mvn`, or comments into that field.

### The four buttons in the approval UI

| Button | What it runs | Writes |
| --- | --- | --- |
| **1 Generate features** | `FeatureWriter` over **APPROVED** cases only (same as `java -jar $JAR features`) | `src/test/resources/features/generated/<tag>.feature`; warns if a step definition is missing |
| **2 Generate services & steps** | `ServiceGenerator` + `StepGenerator` for every spec the cases came from (same as `services --spec`) | `api/generated/<Tag>Api.java`, `steps/generated/<Tag>Steps.java` |
| **3 Execute tests…** | dialog: environment (+ **Check environment**), tag checkboxes (any of / all of, exclude `@wip`) or a free tag expression, threads, optional LLM narrative → `mvn -B test -Denv=… -Dcucumber.filter.tags=…`, live log, then RCA + report merge in-process | `reports/index.html` (with RCA), `target/rca/`, `target/platform/last-run.log` |
| **4 Open report** | opens `/reports/index.html` (the RCA view when tests failed) | – |

The same from a terminal: `./scripts/run-tests.sh qa "@smoke"` then `open reports/index.html#rca`.
Only REVIEWER users can press 1–3 (VIEWER users can watch a run and open the report). One run at a time; the
command is a fixed argument list (no shell), env and tags are validated. Maven: `./mvnw` if present, else `mvn`
(`mvn.cmd` on Windows); override with `PLATFORM_MVN=/path/to/mvn`.

Several specs can live side by side: run `new-spec.sh` for each. Each spec's cases and `<Tag>Api` classes are
replaced only by that spec. The project ships with no API-specific code; `src/test/resources/fixtures/sample-api.yaml`
is only used by the platform's unit tests.

## 1. Architecture

```
                 ┌──────────────────────── src/main (platform, one jar) ─────────────────────────┐
 specs/          │                                                                               │
 YAML/JSON  ──►  │  ingest/            generate/              approval/ (Spring Boot)            │
 Postman    ──►  │  SpecLoader    ──►  CaseGenerator     ──►  UI + REST API  ◄── human reviewer  │
 Markdown   ──►  │  ApiModelMapper     rules/*  (7)           audit trail (H2/Postgres)          │
 URL        ──►  │  adapters/*         CaseDiffer             │                                  │
                 │                     CaseStore ──► testcases/<tag>/<operationId>.json (Git)     │
                 │                                            │ APPROVED                         │
                 │  feature/FeatureWriter  ◄──────────────────┘                                  │
                 └──────────┬────────────────────────────────────────────────────────────────────┘
                            ▼
      src/test/resources/features/generated/*.feature
                            │
                 ┌──────────▼──────────── src/test (automation) ─────────────────────────────────┐
                 │  ApiTestRunner (Cucumber + TestNG, parallel)                                   │
                 │  steps ──► services ──► api/<Tag>Api ──► client/ApiClient ──► Rest Assured     │
                 │  hooks/Hooks ──► target/evidence/<caseId>.json   plugin ──► target/run-summary │
                 └──────────┬────────────────────────────────────────────────────────────────────┘
                            ▼
                 rca/RcaEngine + rules/* (9) [+ optional GitHub Models narrative] ──► target/rca/rca.json
                 org.rg.reporter.ModernCucumberReporter (during mvn test) ──► reports/index.html
                 report/ReportRcaMerger (after RCA) ──► adds rca to the page: #rca view + RCA tab per test
```

Design choices:

- **Git is the source of truth** for test cases (`testcases/`). The UI reads and writes those files; the database only
  keeps the audit trail. Reviews show up in pull requests like code.
- **Stable ids** (`TC-<operationId>-<category>-<detail>`) and a content hash let a spec be re-imported any time:
  unchanged cases keep their approval, changed ones go back to review, removed ones become OBSOLETE.
- **Thin scenarios, generated steps.** A scenario names its case id and its operation
  (`When I call the orders operation "createOrder"`); the request and expectations come from the approved JSON at
  run time. The step definition and the endpoint class for every operation are generated from the spec, so a new
  API needs no hand-written Java.
- **Rules first, LLM second.** RCA categories are deterministic and unit-tested; an LLM (optional, GitHub Models by
  default, so no extra key in Actions) only rewrites the narrative.
- **Human gate is enforced**, not just documented: the UI is the only place to approve, and a Copilot `preToolUse`
  hook blocks agents from approving or editing cases and generated features.

## 1a. Automation design: service-based layers

```
Feature (Gherkin)
  └─ Steps           steps/       ApiSteps (shared Given/Then) · generated/<Tag>Steps (one When per operation) · <Domain>Steps you add
       └─ Services   services/    ServiceFactory → OperationService.prepare (JSON case → Api arguments) · api(<Tag>Api.class) · <Domain>Service (business flows,
            │                     typed models, test-data setup + cleanup)
            └─ Api   api/         BaseApi → generated <Tag>Api: one method per operation, raw ApiResponse, no assertions
                 └─ ApiClient     client/      builds the call: base URI per service, auth strategy, timeouts, JSON
                                               serialisation, masked capture for evidence → Rest Assured
Assertions: ResponseAssert.assertThat(response).hasStatus(200).matchesSchema(schema).hasField("$.name", "rex")
```

Practices applied: single responsibility per layer; immutable `ApiRequest` built with a builder; `ApiResponse` hides
Rest Assured; Strategy (`AuthProvider`) and Factory (`AuthProviders`, `ServiceFactory`) patterns; constructor
injection with PicoContainer, one public constructor each; no shared mutable state between parallel scenarios;
typed records for models; failures as `ApiClientException` with the cause kept; generated code separated from
hand-written code; cleanup registered where data is created.

## 2. Folder structure

```
api-test-platform/
├── pom.xml                         single module: platform (main) + automation (test)
├── Makefile                        shortcuts (make generate / serve / features / test)
├── Dockerfile, docker-compose.yml  shared approval UI with Postgres audit
├── .vscode/                        settings.json (Copilot features on), extensions.json
├── .github/
│   ├── copilot-instructions.md     repo-wide rules for every Copilot surface
│   ├── instructions/               path-specific rules (applyTo): java-platform, automation, testcases, features, prompts
│   ├── agents/                     custom agents (*.agent.md): spec-analyst, test-designer, feature-author,
│   │                               automation-engineer, rca-analyst, pipeline-orchestrator
│   ├── skills/                     agent skills (SKILL.md): swagger-to-testcases, testcases-to-features,
│   │                               run-api-tests, failure-rca, report-rca-tab, add-step-definition, add-api-service
│   ├── prompts/                    prompt files (VS Code slash commands): /generate-tests /review-status
│   │                               /build-features /run-tests /rca /pipeline
│   ├── hooks/guard.json            preToolUse hook → hooks/scripts/guard.py (CLI + cloud agent)
│   └── workflows/
│       ├── api-tests.yml           build + unit, spec-change regeneration, nightly/manual runs
│       └── copilot-setup-steps.yml JDK 17 + Maven + jar for the Copilot cloud agent
├── prompts/                        versioned LLM prompts (used by Java and by agents)
├── scripts/
│   ├── run-tests.sh                tests → RCA → report, exit code = result
│   └── serve.sh                    approval UI
├── config/env/{dev,qa,stage}.properties   base URIs, auth profiles, testdata.*
├── specs/                          put your Swagger/OpenAPI files here (empty in a clean checkout)
├── testcases/                      generated + reviewed JSON cases (commit this)
│   └── _proposed/                  agent proposals before `propose` (git-ignored)
├── history/results.jsonl           run history for flaky / new-failure detection
├── reports/                        generated report: one self-contained index.html (+ history.json for trends)
└── src/
    ├── main/java/com/qa/platform/
    │   ├── Main.java               `serve` → Spring Boot UI, anything else → CLI
    │   ├── cli/PlatformCli.java    ingest, generate, status, propose, services, features, rca, report, post-run
    │   ├── common/                 Json, Hashing, Masking, Workspace, LlmClient, PromptLibrary
    │   ├── model/                  ApiModel, Endpoint, TestCase, Evidence, RcaEntry (records)
    │   ├── ingest/                 SpecLoader, ApiModelMapper, IngestService, adapter/{Postman,Markdown}
    │   ├── generate/               ValueGenerator, CaseFactory, CaseStore, CaseDiffer, rules/*
    │   ├── approval/               controller, service, security, audit entity, report hosting
    │   ├── feature/FeatureWriter.java
    │   ├── rca/                    RcaEngine, RunHistory, LlmRcaEnricher, BugTicketWriter, rules/*
    │   ├── codegen/                ServiceGenerator (<Tag>Api), StepGenerator (<Tag>Steps), CodegenService
    │   └── report/ReportRcaMerger.java   adds target/rca/rca.json into reports/index.html
    ├── main/resources/
    │   ├── application.yml
    │   ├── schemas/                testcase.schema.json, rca.schema.json
    │   └── static/                 approval UI (index.html, app.js, app.css; no build step)
    └── test/
        ├── java/com/qa/automation/
        │   ├── client/             ApiClient (only Rest Assured user), ApiRequest, ApiResponse, auth/ strategies
        │   ├── api/                BaseApi; generated/<Tag>Api (from the `services` command)
        │   ├── services/           ServiceFactory (api(Class) for any generated class), OperationService (JSON cases)
        │   ├── models/             request/response records you add for typed services
        │   ├── assertions/         ResponseAssert (fluent), SchemaValidator
        │   ├── steps/              ApiSteps (shared Given/Then); generated/<Tag>Steps (from the `services` command)
        │   └── runners, hooks, context, support, plugin, config
        ├── java/org/rg/reporter/   ModernCucumberReporter, ReportContext, HtmlReportWriter, ErrorFactory, SourceIndex, model/
        ├── java/com/qa/platform/   platform unit tests (mvn test -Punit)
        └── resources/              testng.xml, cucumber.properties, features/{generated,manual},
                                    modern-reporter/ui/{app.js, styles.css}  (report UI with the RCA view)
```

## 3. Setup (once)

Requirements: JDK 17+, Maven 3.9+, Python 3 (for the Copilot guard hook), `jq` (for scripts), optionally Docker.

```bash
mvn -q test -Punit                 # platform unit tests, no network
mvn -q -DskipTests package         # builds target/api-test-platform.jar
```

Edit `config/env/qa.properties`: set `baseUri`, the auth profile, and `testdata.*` ids.
Secrets go in environment variables referenced as `${VAR}`:

```bash
export QA_API_TOKEN=...            # or QA_CLIENT_ID / QA_CLIENT_SECRET for oauth2
```

## 3a. Running inside your company network

Nothing in the code is tied to one company. What changes is configuration, all of it outside Git or behind
`${ENV_VAR}` references:

| Concern | Where | What to set |
| --- | --- | --- |
| Maven can't download (403, timeouts) | `config/maven/settings-company.xml` | `MAVEN_REPO_URL` (+ `MAVEN_REPO_USER`/`PASSWORD`), then `export MAVEN_ARGS="-s config/maven/settings-company.xml"` (or copy it to `~/.m2/settings.xml`, or use the one IT gives you) |
| Your environment (SIT, UAT, …) | `./scripts/new-env.sh sit` → `config/env/sit.properties` | `baseUri` / `baseUri.<tag>`, `auth.default.*`, `testdata.*` |
| Corporate proxy | same file | `http.proxy.host/port/user/password/nonProxyHosts` |
| `PKIX path building failed` | `config/certs/` (git-ignored) | company root CA in `tls.trustStore` (see `config/certs/README.md`) |
| API gateway needs a client cert (mTLS) | same | `tls.keyStore`, `tls.keyStorePassword` |
| Gateway keys, tracing headers | same | `http.header.Ocp-Apim-Subscription-Key=${APIM_KEY}`, `http.header.X-Correlation-Id=${uuid}` |
| Values only for your laptop | `config/env/<env>.local.properties` (git-ignored) | overrides any key of the shared file |
| Secrets | your shell / CI secrets | `export SIT_API_TOKEN=...`; the files only hold `${SIT_API_TOKEN}` |
| LLM not allowed / company gateway | env | use your IDE agent (no token), or point `LLM_PROVIDER`/`LLM_BASE_URL` at your approved gateway (section 6a) |
| Sharing the approval UI | env | default binds to `127.0.0.1`; `BIND_ADDRESS=0.0.0.0` requires real `PLATFORM_USERS` passwords |

Then check the environment before the first run. It reports missing variables by name (never their values),
proxy, trust store, client certificate and whether every base URI answers, with a hint per failure:

```bash
java -jar target/api-test-platform.jar doctor --env sit
#  OK   proxy                 http://proxy.company.com:8080, bypass: localhost|*.company.internal
#  FAIL baseUri               TLS: PKIX path building failed
#       -> The company CA is not trusted by Java. Export it and set tls.trustStore ...
```

The same check is the **Check environment** button in the Execute dialog. The proxy, trust store and client
certificate apply to every API call and to OAuth token requests.

## 4. Run it, step by step

```bash
JAR=target/api-test-platform.jar

# 1  spec → JSON test cases (file, URL, Postman or Markdown)
java -jar $JAR ingest   --spec specs/your-api.yaml               # what was found + config keys still missing
java -jar $JAR generate --spec specs/your-api.yaml               # writes testcases/, keeps approvals
java -jar $JAR status

# 2  human review
PLATFORM_USERS="alice:change-me:REVIEWER,bob:change-me:VIEWER" java -jar $JAR serve
#    open http://localhost:8080 → filter, read, edit, approve / reject (keys: J K A R C)

# 3  approved cases → feature files                              (UI button 1)
java -jar $JAR features

# 4  <Tag>Api endpoint classes + <Tag>Steps step definitions      (UI button 2)
java -jar $JAR services --spec specs/your-api.yaml
mvn -q test -Dcucumber.execution.dry-run=true                    # every step binds

# 5  run + RCA + report                                           (UI buttons 3 and 4)
./scripts/run-tests.sh qa "@smoke"                               # or: make test ENV=qa TAGS=@smoke
open reports/index.html#rca                                      # also #dashboard #failures #tests ...

# optional: LLM narrative in the RCA via GitHub Models (category stays rule-based)
export GITHUB_TOKEN=<PAT with models:read>  ;  java -jar $JAR post-run --llm
#   LLM_MODEL=openai/gpt-4.1 (default) or any GitHub Models id; LLM_GITHUB_ORG=<org> to bill the org
#   LLM_PROVIDER=anthropic + ANTHROPIC_API_KEY to call Anthropic directly instead
```

Useful filters: `@smoke` (positive cases), `@P1`, `@regression`, `@<tag>` (OpenAPI tag), `@<category>`,
`@TC-<id>` (one case). Parallelism: `-Dthreads=8`. Evidence for passing tests too: `-Devidence.all=true`.

## 5. Using it with GitHub Copilot

| Customization | File(s) | VS Code | Copilot CLI | Cloud agent (GitHub.com) |
| --- | --- | --- | --- | --- |
| Repo instructions | `.github/copilot-instructions.md` | yes | yes | yes |
| Path instructions | `.github/instructions/*.instructions.md` | yes | yes | yes |
| Custom agents | `.github/agents/*.agent.md` | agent picker | `/agent <name>` | agents panel, assign issue |
| Agent skills | `.github/skills/*/SKILL.md` | auto | auto | auto |
| Prompt files | `.github/prompts/*.prompt.md` | `/name` | no | no |
| Hooks | `.github/hooks/guard.json` | preview | yes | yes (default branch) |
| Environment | `.github/workflows/copilot-setup-steps.yml` | - | - | yes |

**VS Code** (Copilot Chat, agent mode): open the repo, pick an agent in the agent picker or type a prompt file:

| You type | What happens |
| --- | --- |
| `/generate-tests specs/orders.yaml` | `spec-analyst` audits the spec; the **Design test cases** handoff runs `test-designer` |
| `/review-status` | what is waiting for review, what to look at first |
| `/build-features` | `feature-author` writes features from APPROVED cases and dry-runs them |
| `/run-tests` (asks env, tags) | runs tests, RCA, report |
| `/rca` | `rca-analyst` checks root causes against evidence and writes `target/rca/triage.md` |
| `/pipeline` | `pipeline-orchestrator` runs the agents as subagents, stopping at the human approval gate |

**Copilot CLI**: run `copilot` in the repo, then `/agent spec-analyst` (or any agent) and describe the task, e.g.
"audit specs/orders.yaml". Skills load automatically; the guard hook runs before every tool call.

**Copilot cloud agent**: create an issue such as "Generate test cases for specs/orders.yaml and propose business
cases", choose the `test-designer` agent, and assign it to Copilot. It builds with `copilot-setup-steps.yml` and opens
a PR with the new PENDING_REVIEW cases; reviewers then approve them in the UI. Or "Analyse the failures in the
attached run" with `rca-analyst`. The cloud agent firewall must allow your API host if the agent should run tests.

Guardrails (`.github/hooks/scripts/guard.py`): agents cannot approve cases, cannot edit `testcases/` except
`_proposed/`, cannot edit generated features, cannot write literal secrets in `config/env`, cannot `git push` from
the shell. In VS Code, where hooks are in preview, keep terminal auto-approve off (`.vscode/settings.json`) and
review each proposed command; the instructions files carry the same rules.

Models: custom agents use the model selected in the Copilot model picker (no `model:` is pinned, so they work on
every plan); pin one with `model: <name>` in an agent's frontmatter if your organisation standardises on it.

## 6. The report (your Modern Cucumber Reporter) and the RCA view

`org.rg.reporter.ModernCucumberReporter` (in `src/test/java/org/rg/reporter`, UI in
`src/test/resources/modern-reporter/ui/`) is registered in `ApiTestRunner` as
`org.rg.reporter.ModernCucumberReporter:reports`, so `mvn test` writes the familiar single-file
`reports/index.html` (Dashboard, Failures, Tests, Categories, Tags, Analytics, Timeline; trends in `history.json`).
For API tests:
- every HTTP call is an **action** step (`ApiClient` → `ReportContext.action`), every check an **expect** step
  (`ResponseAssert` → `ReportContext.expect`), with the console line `POST /orders -> 500 (123 ms)`
- failed tests carry `evidence.json` and `reproduce.curl` attachments (masked)
- project = environment, Environment card shows env, base URI, tags and threads

After the run, `post-run` (or the Execute button) runs the RCA and `ReportRcaMerger` writes `rca` into the page's
`report-data` JSON. The UI then shows:
- an **RCA** view (key `3`): root causes grouped by signature, by category / by owner, new failures, confidence,
  probable cause, suggested fix, affected tests, recent runs, copy cURL / bug ticket / RCA, link to the case in the
  approval UI when served by it
- an **RCA** tab in each failed test's drawer and a root-cause callout on its Errors tab; the Failures cards get an
  RCA button; "Copy prompt" includes the rule-based RCA

Tests are matched to RCA entries through the scenario's `@TC-<id>` tag. Change the look in `styles.css`/`app.js`
there (see `.github/skills/report-rca-tab/SKILL.md`).

## 6a. AI in the platform (optional): LLM API or your IDE agent

Every AI feature is one task with one validation path. The rules (case generation, RCA categories, owners) and
the human approval never depend on it.

| Task | What it does | Where it is used |
| --- | --- | --- |
| `doc-to-openapi` | API documentation (Markdown/HTML/text) → `specs/<doc>.openapi.yaml`, parsed as OpenAPI before it is written | `ai doc-to-openapi --doc docs/api.md`; importing a doc (CLI or UI) uses the same prompt for sections the rules cannot read |
| `spec-audit` | testability gaps and questions for the API owner → `target/platform/spec-audit-<spec>.md` | CLI, UI **AI assist**, `/generate-tests` |
| `testcase-augment` | business cases the rules cannot know → `testcases/` as **Pending review** (schema-checked, never approved) | CLI, UI **AI assist** (with optional business context), `/generate-tests` |
| `rca-narrative` | sharper summary / cause / fix per root cause (category and owner kept) → `rca.json`, tickets, report | `post-run --llm`, UI Execute checkbox, CLI, `/rca` |
| `triage-summary` | the triage note → `target/rca/triage.md` | `post-run --llm`, UI Execute checkbox, CLI, `/rca` |

### Way 1: your IDE agent answers (no token, nothing to configure)
In VS Code Copilot Chat (**Agent** mode) or Claude Code:

```
/ai testcase-augment --spec specs/orders.yaml      (any task, see the table)
/rca                                               (after a run: narrative + triage)
```
Under the hood: `java -jar target/api-test-platform.jar ai <task> ... --mode agent` writes
`target/ai/<task>/requests.md`; the agent writes one answer file per request; `ai <task> --apply --by copilot-agent`
validates and applies them, exactly as for an LLM answer. VS Code tasks "AI task for the agent (export/apply)" do
the same. Use this when your company allows the coding agent but no separate LLM API.

### Way 2: an LLM API, called by the platform
Set these in the shell that runs the CLI or starts the approval UI (UI checkbox and **AI assist** buttons then run
on the server). With no token, `--mode auto` (the default) falls back to Way 1.

| Provider | Variables |
| --- | --- |
| GitHub Models (default) | `GITHUB_TOKEN` = PAT with `models:read` (in Actions: `permissions: models: read`); optional `LLM_MODEL=openai/gpt-4.1`, `LLM_GITHUB_ORG=<org>` |
| Company gateway, OpenAI-compatible (LiteLLM, Kong, Apigee, vLLM, OpenAI) | `LLM_PROVIDER=openai`, `LLM_BASE_URL=https://llm-gateway.company.com/v1`, `LLM_API_KEY`, `LLM_MODEL`; `LLM_AUTH_HEADER=api-key` if not `Authorization: Bearer` |
| Azure OpenAI | `LLM_PROVIDER=azure-openai`, `LLM_BASE_URL=https://<resource>.openai.azure.com`, `LLM_DEPLOYMENT`, `LLM_API_KEY`, optional `LLM_API_VERSION` |
| Anthropic | `LLM_PROVIDER=anthropic`, `ANTHROPIC_API_KEY`, optional `LLM_MODEL`, `LLM_BASE_URL` |

```bash
export LLM_PROVIDER=openai LLM_BASE_URL=https://llm-gateway.company.com/v1 LLM_MODEL=gpt-4.1
export LLM_API_KEY=...                       # from your vault; never in a file
java -jar target/api-test-platform.jar ai spec-audit --spec specs/orders.yaml      # --mode llm to require the API
java -jar target/api-test-platform.jar post-run --llm                             # narrative + triage after a run
```
Company network: `LLM_PROXY=http://proxy:8080` (else `HTTPS_PROXY`); a gateway with an internal certificate needs
the company CA in Java's trust store (`JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=..."`).
Everything sent is masked first; a failed call leaves the rule-based result untouched.

## 7. CI

`.github/workflows/api-tests.yml`:
- every push/PR: unit tests + jar
- PR that changes `specs/`: regenerates cases so reviewers see what changed
- nightly and manual (`env`, `tags` inputs): features → tests → RCA → report artifact, run history cached,
  top root causes in the job summary

Secrets to configure: `QA_API_TOKEN` (or your auth vars). For the LLM narrative set the repository variable
`LLM_ENABLED=true` (optional `LLM_MODEL`); the workflow's `models: read` permission lets `GITHUB_TOKEN` call GitHub
Models, so no extra key is needed.

## 8. Extending

| To add | Do this |
| --- | --- |
| a test category | class in `generate/rules/`, register in `CaseGenerator`, add to the schema enum, unit test |
| an RCA category | class in `rca/rules/`, register in `RcaEngine`, add to `rca.schema.json`, unit test |
| a step | per operation: generated by `services` (never edit `steps/generated/`); generic check: `steps/ApiSteps.java` (and `FeatureWriter`); business flow: a domain `*Steps` class backed by a service |
| typed calls for an API | `java -jar target/api-test-platform.jar services --spec <spec>` (Api + Steps), then a `*Service` + models (skill `add-api-service`) |
| an auth type | a class in `client/auth/` implementing `AuthProvider`, registered in `AuthProviders`, + example in `config/env/qa.properties` |
| an input format | implement `ingest/adapter/DocAdapter`, register in `IngestService` |

## 9. Status of this scaffold

Written as production-grade code but **not yet compiled against Maven Central** (it was authored offline).
Expect a short first-build pass: run `mvn -q test -Punit`, fix any compile errors, and check the expected
counts in `GeneratorTest` against `src/test/resources/fixtures/sample-api.yaml`. The approval UI and the report were rendered and
checked in a browser with sample data (report: RCA view, RCA tab, failures; UI: pipeline bar and Execute dialog);
generated `<Tag>Api`/`<Tag>Steps` were compiled against the project's own classes; the Copilot guard hook was tested with Copilot CLI/cloud-agent and
VS Code payloads for allowed and blocked actions.
