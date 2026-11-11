---
description: Conventions for the platform code (ingest, generation, approval, RCA, report)
applyTo: "src/main/java/**/*.java"
---
- Data types are Java records in `com.qa.platform.model`; add `with...` copy methods instead of setters.
- Only `ingest/` may import `io.swagger.*`. Everything after ingest works on `ApiModel` and Jackson `JsonNode`.
- Use `com.qa.platform.common.Json.MAPPER`; never create another `ObjectMapper` except swagger's in `ApiModelMapper`.
- A new test category = one class in `generate/rules/` implementing `CaseRule`, registered in `CaseGenerator`,
  added to the `category` enum in `src/main/resources/schemas/testcase.schema.json`, with a unit test.
- A new failure category = one class in `rca/rules/` implementing `RcaRule`, registered in `RcaEngine.rules`,
  added to `rca.schema.json`, with a case in `FeatureAndRcaTest`.
- Anything written to evidence, reports or sent to an LLM goes through `Masking`.
- LLM calls go through `LlmClient` only and must have a rule-based fallback when the LLM is off or fails.
