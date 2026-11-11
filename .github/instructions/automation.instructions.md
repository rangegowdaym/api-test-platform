---
description: Layering and rules for the Rest Assured + Cucumber + TestNG automation code
applyTo: "src/test/java/com/qa/automation/**"
---
Service-based layering. Each layer only calls the one below it:

| Layer | Package | Rule |
| --- | --- | --- |
| Steps | `steps`, `steps.generated` | Gherkin glue only. No HTTP, no Rest Assured, no JSON parsing. Call services, assert with `ResponseAssert`. `steps.generated` (`<Tag>Steps`, one When per operation → `<Tag>Api`) is written by the `services` command; never edit it. |
| Services | `services` | Business flows on one domain area (e.g. `OrderService`). Typed models in, typed models out. Assert success when creating test data and register cleanup with `ctx.addCleanup`. |
| Api (endpoints) | `api`, `api.generated` | One class per OpenAPI tag, one method per operation, raw `ApiResponse` out, no assertions. `api.generated` is written by the `services` command; never edit it. |
| Client | `client`, `client.auth` | `ApiClient` is the ONLY class that uses Rest Assured for API calls. Auth is a strategy (`AuthProvider`). |
| Models | `models` | Java records with `@JsonIgnoreProperties(ignoreUnknown = true)` and `@JsonInclude(NON_NULL)`. |
| Assertions | `assertions` | `ResponseAssert` (fluent) and `SchemaValidator`. |

- Get services through `ServiceFactory` (injected by PicoContainer); `services.asUser("admin")` switches the auth profile.
- Scenario state lives in `ScenarioContext` (one per scenario). No static mutable state except thread-safe caches
  (`AuthProviders`, `SchemaValidator`).
- Classes PicoContainer creates (steps, hooks, `ApiClient`, `ServiceFactory`) have exactly ONE public constructor.
- Keep failure messages in the shapes the RCA rules read (they come from `ResponseAssert`): `Expected status X but was Y`,
  `Schema validation failed ...`, `JSON path $.a ... expected [..] but was [..]`, `Response time N ms exceeded M ms`,
  `Missing test data: ...`.
- Transport errors surface as `ApiClientException` with the cause kept; never catch and swallow them in services.
- Retries are for network errors only (`NetworkRetry`). Never retry or relax an assertion to hide a failure.
- If a generic step text changes, update `FeatureWriter.render()` and the preview in `src/main/resources/static/app.js`.
  The generated When-step text comes from `StepGenerator.stepText()`, used by both `FeatureWriter` and `StepGenerator`.
- Report hooks: `ApiClient` wraps each call in `ReportContext.action`, `ResponseAssert` each check in
  `ReportContext.expect` (both no-ops without the report plugin). Keep those wrappers when you change them.
